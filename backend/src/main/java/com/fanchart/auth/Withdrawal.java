package com.fanchart.auth;

import com.fanchart.account.AccountStore;
import com.fanchart.trading.NotFoundException;
import com.fanchart.trading.StoredOrder;
import com.fanchart.trading.TradingException;
import com.fanchart.trading.TradingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 탈퇴. 계정 행과 원장은 다른 계정의 잔고·체결과 맞물려 있어 지우지 않는다. 대신
 * <ul>
 * <li>로그인 연결(account_identities)을 지워 그 사람과 계정의 연결을 끊고, 닉네임을 "탈퇴회원+숫자"로 바꾼다.</li>
 * <li>대기 주문을 모두 취소한다. 남은 포인트와 주식은 그대로 두어 장부 합계가 바뀌지 않는다(소각하지 않는다).</li>
 * <li>로그인 식별 번호의 SHA-256 해시만 30일 남겨, 탈퇴와 재가입을 되풀이해 가입 포인트를 거듭 받지 못하게 한다.</li>
 * <li>다른 기기에 남은 세션은 WithdrawnSessionFilter가 다음 요청에서 끊는다.</li>
 * </ul>
 */
@Service
public class Withdrawal {

    public static final Duration REJOIN_BLOCK = Duration.ofDays(30);
    private static final Logger log = LoggerFactory.getLogger(Withdrawal.class);

    private final AccountStore accounts;
    private final TradingService trading;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Set<String> withdrawn = ConcurrentHashMap.newKeySet();

    Withdrawal(AccountStore accounts, TradingService trading, JdbcTemplate jdbc, TransactionTemplate tx, Clock clock) {
        this.accounts = accounts;
        this.trading = trading;
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        withdrawn.addAll(accounts.withdrawnIds());
    }

    public boolean isWithdrawn(String accountId) {
        return withdrawn.contains(accountId);
    }

    public void withdraw(String accountId) {
        Instant now = clock.instant();
        tx.executeWithoutResult(s -> {
            if (!accounts.lockRow(accountId)) throw new NotFoundException("없는 계정이에요");
            if (accounts.find(accountId).orElseThrow().withdrawn()) return;
            for (int tries = 1; ; tries++) {
                try {
                    accounts.markWithdrawn(accountId, "탈퇴회원" + ThreadLocalRandom.current().nextInt(100_000, 1_000_000), now);
                    break;
                } catch (DuplicateKeyException e) {
                    if (tries == 10) throw new IllegalStateException("could not pick a free nickname", e);
                }
            }
            for (var id : jdbc.queryForList("SELECT provider, subject FROM account_identities WHERE account_id = ?",
                    accountId)) {
                jdbc.update("DELETE FROM withdrawn_identities WHERE identity_hash = ?",
                        hash((String) id.get("provider"), (String) id.get("subject")));
                jdbc.update("INSERT INTO withdrawn_identities (identity_hash, withdrawn_at) VALUES (?, ?)",
                        hash((String) id.get("provider"), (String) id.get("subject")), Timestamp.from(now));
            }
            jdbc.update("DELETE FROM account_identities WHERE account_id = ?", accountId);
        });
        // 이 뒤로 들어오는 요청은 필터가 막으므로, 지금 있는 대기 주문을 취소하면 더 생기지 않는다
        withdrawn.add(accountId);
        for (StoredOrder o : trading.openOrders(accountId)) {
            try {
                trading.cancel(accountId, o.id());
            } catch (NotFoundException e) {
                // 그 사이 체결되거나 취소됐다
            }
        }
        jdbc.update("DELETE FROM withdrawn_identities WHERE withdrawn_at < ?", Timestamp.from(now.minus(REJOIN_BLOCK)));
        log.info("account {} withdrew", accountId);
    }

    /** 탈퇴하고 30일이 안 된 로그인 수단이면 가입을 막는다 */
    void checkRejoin(String provider, String subject) {
        Instant since = clock.instant().minus(REJOIN_BLOCK);
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM withdrawn_identities WHERE identity_hash = ? AND withdrawn_at > ?",
                Integer.class, hash(provider, subject), Timestamp.from(since));
        if (n != null && n > 0) throw new TradingException("탈퇴하고 30일이 지나야 다시 가입할 수 있어요");
    }

    static String hash(String provider, String subject) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((provider + ":" + subject).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
