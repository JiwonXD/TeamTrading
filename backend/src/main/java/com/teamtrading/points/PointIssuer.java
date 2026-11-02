package com.teamtrading.points;

import com.teamtrading.account.AccountStore;
import com.teamtrading.ledger.Journal;
import com.teamtrading.ledger.Ledger;
import com.teamtrading.trading.IdempotencyConflictException;
import com.teamtrading.trading.NotFoundException;
import com.teamtrading.trading.TradingException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 포인트 발행. 포인트를 새로 만드는 코드는 여기 하나뿐이다.
 * 발행 기록(point_grants), 원장 전표, 잔고 변경을 한 트랜잭션에서 남긴다.
 * 발행분은 수익률 기준(initial_value)에도 더해 활동 보상이 투자 수익으로 잡히지 않게 한다.
 */
@Service
public class PointIssuer {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final AccountStore accounts;
    private final PointStore store;
    private final Ledger ledger;
    private final TransactionTemplate tx;
    private final Clock clock;

    PointIssuer(AccountStore accounts, PointStore store, Ledger ledger, TransactionTemplate tx, Clock clock) {
        this.accounts = accounts;
        this.store = store;
        this.ledger = ledger;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * 활동 보상 지급. 같은 (source, sourceRef)로 다시 부르면 새로 지급하지 않고 처음 기록을 돌려준다.
     * 계정 행을 잠근 뒤 오늘 지급 횟수를 세므로 동시 요청이 한도를 넘지 못한다.
     */
    public PointGrant grant(String accountId, PointSource source, String sourceRef) {
        if (source == PointSource.SIGNUP) throw new IllegalArgumentException("signup is granted on account opening");
        return tx.execute(s -> {
            if (!accounts.lockRow(accountId)) throw new NotFoundException("계정을 찾을 수 없어요");
            var existing = store.find(source, sourceRef);
            if (existing.isPresent()) {
                if (!existing.get().accountId().equals(accountId))
                    throw new IdempotencyConflictException("이미 다른 계정에 지급된 보상이에요");
                return existing.get();
            }
            Instant now = clock.instant();
            Instant dayStart = now.atZone(KST).toLocalDate().atStartOfDay(KST).toInstant();
            if (store.countSince(accountId, source, dayStart) >= source.dailyLimit())
                throw new TradingException("오늘 받을 수 있는 보상을 모두 받았어요");
            return issue(accountId, source, sourceRef, source.amount(), now);
        });
    }

    /** 가입 지급. 계정을 만드는 트랜잭션 안에서 부른다 */
    public PointGrant grantSignup(String accountId) {
        return tx.execute(s -> issue(accountId, PointSource.SIGNUP, accountId, PointSource.SIGNUP.amount(),
                clock.instant()));
    }

    private PointGrant issue(String accountId, PointSource source, String sourceRef, long amount, Instant now) {
        long journalId = ledger.post(Journal.issue(accountId, amount), now);
        accounts.applyPoints(accountId, amount, 0);
        accounts.addInitialValue(accountId, amount);
        return store.insert(accountId, source, sourceRef, amount, journalId, now);
    }
}
