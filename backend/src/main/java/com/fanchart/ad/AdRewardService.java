package com.fanchart.ad;

import com.fanchart.account.AccountStore;
import com.fanchart.points.PointIssuer;
import com.fanchart.points.PointSource;
import com.fanchart.trading.IdempotencyConflictException;
import com.fanchart.trading.NotFoundException;
import com.fanchart.trading.TradingException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

/**
 * 광고 시청 보상.
 * <ul>
 * <li>앱은 광고를 띄우기 전에 세션을 열고 세션 id를 광고 네트워크에 custom_data로 넘긴다.
 * 포인트는 앱의 "다 봤어요" 신호가 아니라 네트워크가 서명해 보낸 서버 콜백으로만 준다.</li>
 * <li>세션은 한 번만 완료된다. 같은 거래 id로 콜백이 다시 오면(네트워크 재시도) 처음 결과를 돌려준다.</li>
 * <li>지급은 point_grants(AD_VIEW, 네트워크:거래 id) 멱등키와 하루 한도(10회)를 따른다.
 * 한도를 넘긴 콜백은 LIMIT로 끝내고 지급하지 않는다.</li>
 * <li>세션은 1시간 안에 완료돼야 하고, 계정당 하루 50개까지 연다.</li>
 * </ul>
 */
@Service
@EnableConfigurationProperties(AdProperties.class)
public class AdRewardService {

    static final Duration SESSION_TTL = Duration.ofHours(1);
    static final int DAILY_SESSIONS = 50;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** @param paidToday 오늘 받은 횟수 */
    public record Status(long amount, int dailyLimit, int paidToday) {
    }

    private final AdSessionStore store;
    private final AccountStore accounts;
    private final PointIssuer points;
    private final TransactionTemplate tx;
    private final Clock clock;

    AdRewardService(AdSessionStore store, AccountStore accounts, PointIssuer points, TransactionTemplate tx,
                    Clock clock) {
        this.store = store;
        this.accounts = accounts;
        this.points = points;
        this.tx = tx;
        this.clock = clock;
    }

    public Status status(String accountId) {
        return new Status(PointSource.AD_VIEW.amount(), PointSource.AD_VIEW.dailyLimit(),
                points.grantedToday(accountId, PointSource.AD_VIEW));
    }

    public AdSession open(String accountId) {
        return tx.execute(s -> {
            if (!accounts.lockRow(accountId)) throw new NotFoundException("계정을 찾을 수 없어요");
            if (points.grantedToday(accountId, PointSource.AD_VIEW) >= PointSource.AD_VIEW.dailyLimit())
                throw new TradingException("오늘 받을 수 있는 광고 보상을 모두 받았어요");
            Instant now = clock.instant();
            Instant dayStart = now.atZone(KST).toLocalDate().atStartOfDay(KST).toInstant();
            if (store.countSince(accountId, dayStart) >= DAILY_SESSIONS)
                throw new TradingException("오늘은 광고를 더 열 수 없어요");
            String id = UUID.randomUUID().toString();
            store.insert(id, accountId, now);
            return store.find(id).orElseThrow();
        });
    }

    /** 내 세션. 다른 계정의 세션은 없는 것으로 본다 */
    public AdSession find(String accountId, String sessionId) {
        return store.find(sessionId).filter(a -> a.accountId().equals(accountId))
                .orElseThrow(() -> new NotFoundException("광고 세션을 찾을 수 없어요"));
    }

    /**
     * 검증된 네트워크 콜백으로 세션을 끝낸다. 세션 행을 잠그고 처리하므로 같은 세션에 콜백이 동시에 와도 한 번만 지급한다.
     *
     * @param transactionRef 네트워크:거래 id. 포인트 발행 멱등키가 된다
     */
    public AdSession complete(String sessionId, String transactionRef) {
        return tx.execute(s -> {
            AdSession session = store.lock(sessionId)
                    .orElseThrow(() -> new NotFoundException("광고 세션을 찾을 수 없어요"));
            if (session.status() != AdSession.Status.PENDING) {
                if (transactionRef.equals(session.transactionRef())) return session;
                throw new IdempotencyConflictException("이미 끝난 광고 세션이에요");
            }
            Instant now = clock.instant();
            if (session.createdAt().plus(SESSION_TTL).isBefore(now))
                throw new TradingException("시간이 지난 광고 세션이에요");
            boolean paid = points.grantWithinLimit(session.accountId(), PointSource.AD_VIEW, transactionRef).isPresent();
            store.complete(sessionId, paid ? AdSession.Status.PAID : AdSession.Status.LIMIT, transactionRef, now);
            return store.find(sessionId).orElseThrow();
        });
    }
}
