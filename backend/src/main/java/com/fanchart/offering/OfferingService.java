package com.fanchart.offering;

import com.fanchart.account.AccountStore;
import com.fanchart.engine.OrderType;
import com.fanchart.engine.PriceRules;
import com.fanchart.ledger.Journal;
import com.fanchart.ledger.Ledger;
import com.fanchart.offering.Allocation.Request;
import com.fanchart.offering.Offering.Status;
import com.fanchart.offering.OfferingStore.Demand;
import com.fanchart.team.Team;
import com.fanchart.team.TeamCatalog;
import com.fanchart.trading.IdempotencyConflictException;
import com.fanchart.trading.NotFoundException;
import com.fanchart.trading.TradingException;
import com.fanchart.trading.TradingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 공모 청약과 배정.
 * <ul>
 * <li>청약하면 수량 × 공모가를 바로 묶는다(증거금 100%). 배정 때 모자랄 일이 없다.</li>
 * <li>청약·취소·배정은 공모 행을 잠그고 상태와 시각을 확인한다. 마감 직전 청약이 커밋되기 전에 배정이 시작돼도
 * 배정은 그 청약의 잠금이 풀리길 기다렸다가 함께 배정한다.</li>
 * <li>배정은 한 트랜잭션이다. 계정 행은 id 순서로 갱신하고, 보유 수량은 종목 락 안에서 바꾼다(체결 정산과 같은 순서).</li>
 * <li>배정분 대금은 발행처로 돌려 소각한다. 공모 수량을 다 팔지 못하면 남은 주식은 발행하지 않는다.</li>
 * <li>등록할 때 그 구단 주식이 하나도 없었으면 첫 상장이다. 배정 전까지는 그 구단 주문을 받지 않고, 배정 뒤 상장 시각까지는
 * 동시호가로 지정가 주문을 모았다가 상장 시각에 한 가격으로 체결한다. 이미 주식이 있는 구단의 공모는 추가 공모라 거래를 막지 않는다.</li>
 * </ul>
 */
@Service
@EnableConfigurationProperties(OfferingProperties.class)
public class OfferingService {

    private static final Logger log = LoggerFactory.getLogger(OfferingService.class);

    /** 공모 현황. 청약 수량 합과 청약자 수를 함께 준다 */
    public record View(Offering offering, String teamName, long subscribers, long requested) {
    }

    private final OfferingStore store;
    private final TeamCatalog catalog;
    private final TradingService trading;
    private final AccountStore accounts;
    private final Ledger ledger;
    private final TransactionTemplate tx;
    private final Clock clock;

    OfferingService(OfferingStore store, TeamCatalog catalog, TradingService trading, AccountStore accounts,
                    Ledger ledger, TransactionTemplate tx, Clock clock) {
        this.store = store;
        this.catalog = catalog;
        this.trading = trading;
        this.accounts = accounts;
        this.ledger = ledger;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * 공모 등록. price가 null이면 현재가(거래가 없었으면 기준가)로 한다.
     * 공모가는 그날 가격제한폭 안의 호가 단위여야 한다. 한 구단에 청약 중인 공모는 하나만 둔다
     */
    public Offering create(String teamId, Long price, long totalShares, long maxPerAccount, Instant subscribeFrom,
                           Instant subscribeTo, Instant listingAt) {
        Team team = catalog.find(teamId).orElseThrow(() -> new NotFoundException("없는 구단이에요"));
        var quote = trading.quote(teamId);
        long p = price != null ? price : quote.lastPrice();
        PriceRules.validate(OrderType.LIMIT, p, totalShares, quote.basePrice()).ifPresent(reason -> {
            throw new TradingException("공모가: " + reason);
        });
        if (maxPerAccount <= 0 || maxPerAccount > totalShares) throw new TradingException("1인 한도는 1주 이상, 공모 수량 이하여야 해요");
        if (!subscribeFrom.isBefore(subscribeTo) || listingAt.isBefore(subscribeTo))
            throw new TradingException("청약 시작 < 청약 마감 ≤ 상장 순서여야 해요");
        if (!subscribeTo.isAfter(clock.instant())) throw new TradingException("청약 마감이 이미 지났어요");
        // 거래 차단은 같은 종목 락 안에서 건다. 등록이 커밋된 뒤 차단 전에 주문이 끼어들 틈이 없다
        return trading.underMarketLock(teamId, () -> {
            Offering created = tx.execute(s -> {
                if (store.hasOpen(teamId)) throw new TradingException("이 구단은 이미 청약 중인 공모가 있어요");
                int round = store.nextRound(teamId);
                boolean first = !store.sharesExist(teamId);
                long id = store.insert(teamId, round, first, p, totalShares, maxPerAccount, subscribeFrom, subscribeTo,
                        listingAt, clock.instant());
                return store.find(id).orElseThrow();
            });
            if (created.firstListing()) trading.holdUntil(teamId, holdFor(created));
            return created;
        });
    }

    /** 청약. 같은 수량으로 다시 보내면 처음 청약을 그대로 돌려주고, 다른 수량이면 거절한다(먼저 취소) */
    public Subscription subscribe(long offeringId, String accountId, long quantity) {
        if (quantity <= 0) throw new TradingException("1주 이상 청약해 주세요");
        return tx.execute(s -> {
            Offering o = store.lock(offeringId).orElseThrow(() -> new NotFoundException("없는 공모예요"));
            var existing = store.subscription(offeringId, accountId);
            if (existing.isPresent()) {
                if (existing.get().quantity() == quantity) return existing.get();
                throw new IdempotencyConflictException("이미 청약했어요. 수량을 바꾸려면 취소하고 다시 청약해 주세요");
            }
            if (!o.open(clock.instant())) throw new TradingException("청약 기간이 아니에요");
            if (quantity > o.maxPerAccount()) throw new TradingException("1인 청약 한도는 " + o.maxPerAccount() + "주예요");
            long deposit = Math.multiplyExact(quantity, o.price());
            if (!accounts.lockPoints(accountId, deposit)) throw new TradingException("청약금(" + deposit + "P)이 부족해요");
            ledger.post(Journal.subscribe(accountId, deposit), clock.instant());
            store.insertSubscription(offeringId, accountId, quantity, deposit, clock.instant());
            return store.subscription(offeringId, accountId).orElseThrow();
        });
    }

    /** 청약 기간 안에만 취소할 수 있다 */
    public void cancelSubscription(long offeringId, String accountId) {
        tx.executeWithoutResult(s -> {
            Offering o = store.lock(offeringId).orElseThrow(() -> new NotFoundException("없는 공모예요"));
            Subscription sub = store.subscription(offeringId, accountId)
                    .orElseThrow(() -> new NotFoundException("청약한 내역이 없어요"));
            if (!o.open(clock.instant())) throw new TradingException("청약 기간이 끝나 취소할 수 없어요");
            accounts.applyPoints(accountId, sub.deposit(), -sub.deposit());
            ledger.post(Journal.unsubscribe(accountId, sub.deposit()), clock.instant());
            store.deleteSubscription(offeringId, accountId);
        });
    }

    /** 청약 마감이 지난 공모를 모두 배정한다. 하나가 실패해도 나머지는 배정한다 */
    public int allocateDue() {
        int done = 0;
        for (long id : store.due(clock.instant())) {
            try {
                if (allocate(id)) done++;
            } catch (RuntimeException e) {
                log.error("allocation failed for offering {}", id, e);
            }
        }
        return done;
    }

    /** 배정. 청약 마감 전이거나 이미 끝났으면 false */
    public boolean allocate(long offeringId) {
        String teamId = store.find(offeringId).orElseThrow(() -> new NotFoundException("없는 공모예요")).teamId();
        Offering done = trading.underMarketLock(teamId, () -> {
            Offering o = tx.execute(s -> allocateLocked(offeringId));
            if (o != null && o.firstListing()) trading.holdUntil(teamId, holdFor(o));
            return o;
        });
        if (done == null) return false;
        log.info("offering {} allocated {} / {} shares", offeringId, done.allocatedShares(), done.totalShares());
        return true;
    }

    private Offering allocateLocked(long offeringId) {
        Offering o = store.lock(offeringId).orElseThrow();
        if (o.status() != Status.SUBSCRIBING || clock.instant().isBefore(o.subscribeTo())) return null;
        List<Subscription> subs = store.subscriptions(offeringId);
        Map<String, Long> result = Allocation.allocate(offeringId, o.totalShares(),
                subs.stream().map(x -> new Request(x.accountId(), x.quantity())).toList());
        long total = 0;
        for (Subscription sub : subs) {
            long qty = result.get(sub.accountId());
            long paid = qty * o.price();
            accounts.applyPoints(sub.accountId(), sub.deposit() - paid, -sub.deposit());
            if (qty > 0) accounts.applyShares(sub.accountId(), o.teamId(), qty, 0, qty, paid);
            ledger.post(Journal.allot(sub.accountId(), o.teamId(), sub.deposit(), qty, o.price()), clock.instant());
            store.setAllocated(offeringId, sub.accountId(), qty);
            total += qty;
        }
        store.markAllocated(offeringId, total, clock.instant());
        return store.find(offeringId).orElseThrow();
    }

    /** 운영자 취소. 청약 중일 때만, 모든 청약금을 돌려준다 */
    public Offering cancel(long offeringId) {
        String teamId = store.find(offeringId).orElseThrow(() -> new NotFoundException("없는 공모예요")).teamId();
        return trading.underMarketLock(teamId, () -> {
            Offering o = tx.execute(s -> cancelLocked(offeringId));
            // 운영자가 등록한 구단은 첫 상장 공모가 취소되면 다시 공모할 때까지 거래하지 않는다
            if (o.firstListing())
                trading.holdUntil(teamId, catalog.find(teamId).filter(Team::ipoRequired).isPresent() ? Instant.MAX : null);
            return o;
        });
    }

    private Offering cancelLocked(long offeringId) {
        Offering o = store.lock(offeringId).orElseThrow();
        if (o.status() != Status.SUBSCRIBING) throw new TradingException("청약 중인 공모만 취소할 수 있어요");
        for (Subscription sub : store.subscriptions(offeringId)) {
            accounts.applyPoints(sub.accountId(), sub.deposit(), -sub.deposit());
            ledger.post(Journal.unsubscribe(sub.accountId(), sub.deposit()), clock.instant());
            store.deleteSubscription(offeringId, sub.accountId());
        }
        store.markCanceled(offeringId);
        return store.find(offeringId).orElseThrow();
    }

    public View view(long offeringId) {
        return view(store.find(offeringId).orElseThrow(() -> new NotFoundException("없는 공모예요")));
    }

    public List<View> recent(int limit) {
        return store.recent(limit).stream().map(this::view).toList();
    }

    public List<View> byTeam(String teamId) {
        return store.byTeam(teamId).stream().map(this::view).toList();
    }

    public List<Subscription> mine(String accountId, int limit) {
        return store.mine(accountId, limit);
    }

    private View view(Offering o) {
        Demand d = store.demand(o.id());
        String name = catalog.find(o.teamId()).map(Team::name).orElse(o.teamId());
        return new View(o, name, d.subscribers(), d.requested());
    }

    /** 배정 전에는 무기한, 배정 뒤에는 상장 시각까지, 취소됐으면 막지 않는다 */
    private static Instant holdFor(Offering o) {
        return switch (o.status()) {
            case SUBSCRIBING -> Instant.MAX;
            case ALLOCATED -> o.listingAt();
            case CANCELED -> null;
        };
    }
}
