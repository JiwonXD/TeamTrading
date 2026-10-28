package com.teamtrading.trading;

import com.teamtrading.account.Account;
import com.teamtrading.account.AccountStore;
import com.teamtrading.account.Holding;
import com.teamtrading.engine.Level;
import com.teamtrading.engine.MatchResult;
import com.teamtrading.engine.Order;
import com.teamtrading.engine.OrderType;
import com.teamtrading.engine.PriceRules;
import com.teamtrading.engine.Side;
import com.teamtrading.engine.Trade;
import com.teamtrading.fds.Alert;
import com.teamtrading.fds.FdsDetector;
import com.teamtrading.team.Team;
import com.teamtrading.team.TeamCatalog;
import com.teamtrading.trading.StoredOrder.Status;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 주문 접수부터 체결, 정산까지. 종목마다 락을 하나 두어 같은 종목의 주문은 한 줄로 처리하고
 * 다른 종목끼리는 동시에 처리한다. 매칭은 메모리 호가창에서, 잔고와 주문 상태는 DB에서 관리한다.
 *
 * <p>주문 하나는 트랜잭션 두 개로 처리한다.
 * <ol>
 *   <li>접수: 포인트·주식을 조건부 UPDATE로 묶고 주문을 PENDING으로 저장한다. 잔고가 모자라면 여기서 끝난다.</li>
 *   <li>정산: 메모리에서 매칭한 결과(체결, 상대 주문 잔량, 차액 환불)를 한 번에 반영한다.</li>
 * </ol>
 * 정산이 실패하면 DB는 접수 직후 상태로 남으므로 그 주문을 취소하고 호가창을 DB에서 다시 쌓는다.
 * 서버가 두 트랜잭션 사이에 죽으면 재시작 때 PENDING 주문을 같은 방식으로 취소한다.
 */
@Service
public class TradingService {

    public static final long START_POINTS = 1_000_000;
    private static final int MAX_ALERTS = 200;
    private static final Logger log = LoggerFactory.getLogger(TradingService.class);

    private final AccountStore accounts;
    private final OrderStore orders;
    private final TradeStore trades;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Map<String, Market> markets = new LinkedHashMap<>();
    private final Deque<Alert> alerts = new ConcurrentLinkedDeque<>();
    private final AtomicLong ids = new AtomicLong();

    public TradingService(TeamCatalog catalog, AccountStore accounts, OrderStore orders, TradeStore trades,
                          TransactionTemplate tx, Clock clock) {
        this.accounts = accounts;
        this.orders = orders;
        this.trades = trades;
        this.tx = tx;
        this.clock = clock;
        for (Team team : catalog.all()) markets.put(team.id(), new Market(team));
    }

    /** 기동 시 DB를 기준으로 호가창과 시세를 다시 만든다 */
    @PostConstruct
    public void recover() {
        ids.set(Math.max(orders.maxId(), trades.maxId()));
        for (Market m : markets.values()) {
            for (StoredOrder pending : orders.byStatus(m.team.id(), Status.PENDING)) cancelPending(pending);
            rebuildBook(m);
            TradeStore.Stats stats = trades.stats(m.team.id());
            m.restoreQuote(trades.recent(m.team.id(), Market.RECENT_TRADES), stats.high(), stats.low(), stats.volume());
        }
    }

    public Account openAccount(String nickname) {
        return accounts.create(UUID.randomUUID().toString(), nickname, START_POINTS, clock.instant());
    }

    /**
     * 클라이언트가 정한 주문 번호(clientOrderId)로 한 번만 접수한다. 응답을 못 받아 같은 번호로 다시 보내면
     * 주문을 새로 넣지 않고 처음 결과를 돌려준다. 접수 전에 거절된 주문은 저장하지 않아 같은 번호로 다시 보낼 수 있다.
     */
    public OrderReceipt place(String accountId, String clientOrderId, String teamId, Side side, OrderType type,
                              long price, long quantity) {
        Market market = market(teamId);
        account(accountId);
        long base = market.team.basePrice();
        PriceRules.validate(type, price, quantity, base).ifPresent(reason -> {
            throw new TradingException(reason);
        });
        // 시장가 매수는 상한가 기준으로 포인트를 묶어두고 체결 후 차액을 돌려준다
        long reservePerShare = type == OrderType.LIMIT ? price : PriceRules.upperLimit(base);

        market.lock.lock();
        try {
            if (market.halted) throw new TradingException("이 구단은 점검 중이라 잠시 거래할 수 없어요");
            var existing = orders.findByClientOrderId(accountId, clientOrderId);
            if (existing.isPresent()) return replay(existing.get(), teamId, side, type, price, quantity);

            Order order = new Order(ids.incrementAndGet(), accountId, teamId, side, type, price, quantity, clock.instant());
            try {
                tx.executeWithoutResult(s -> accept(order, clientOrderId, reservePerShare));
            } catch (DuplicateKeyException e) {
                // 같은 번호의 주문이 다른 종목 락 아래에서 먼저 저장됐다
                return replay(orders.findByClientOrderId(accountId, clientOrderId).orElseThrow(),
                        teamId, side, type, price, quantity);
            }

            MatchResult result = market.book.submit(order, ids::incrementAndGet, order.createdAt());
            OrderReceipt receipt = OrderReceipt.of(order.id(), type, quantity, result.filledQuantity(),
                    result.resting() == null ? 0 : result.resting().remaining(), result.canceledQuantity(),
                    result.selfTradePrevented());
            try {
                tx.executeWithoutResult(s -> settle(market, order, result, receipt, reservePerShare));
            } catch (RuntimeException e) {
                log.error("settlement failed for order {}", order.id(), e);
                recoverFailedSettlement(market, order.id());
                throw new TradingException("주문을 처리하지 못해 취소했어요. 잠시 후 다시 시도해 주세요");
            }

            for (Trade trade : result.trades()) {
                recordAlerts(FdsDetector.detect(trade, market.lastPrice(), market.recentTrades(Integer.MAX_VALUE)));
                market.record(trade);
            }
            return receipt;
        } finally {
            market.lock.unlock();
        }
    }

    public void cancel(String accountId, long orderId) {
        StoredOrder stored = orders.find(orderId)
                .filter(o -> o.accountId().equals(accountId) && o.status() == Status.OPEN)
                .orElseThrow(() -> new NotFoundException("대기 중인 주문이 아니에요"));
        Market market = market(stored.teamId());
        market.lock.lock();
        try {
            Order order = market.book.find(orderId)
                    .orElseThrow(() -> new NotFoundException("대기 중인 주문이 아니에요"));
            tx.executeWithoutResult(s -> {
                if (!orders.cancel(orderId, Status.OPEN)) throw new NotFoundException("대기 중인 주문이 아니에요");
                release(stored.accountId(), stored.teamId(), stored.side(), stored.reservePerShare(), order.remaining());
            });
            market.book.cancel(orderId);
        } finally {
            market.lock.unlock();
        }
    }

    // ---------- 트랜잭션 본문 ----------

    private void accept(Order order, String clientOrderId, long reservePerShare) {
        boolean locked = order.side() == Side.BUY
                ? accounts.lockPoints(order.accountId(), reservePerShare * order.quantity())
                : accounts.lockShares(order.accountId(), order.teamId(), order.quantity());
        if (!locked) throw new TradingException(order.side() == Side.BUY
                ? "주문 가능 포인트가 부족해요" : "판매 가능 수량이 부족해요");
        orders.insertPending(order, clientOrderId, reservePerShare);
    }

    /**
     * 매칭 결과를 반영한다. 계정 행은 id 순서로 한 번씩만 갱신해서,
     * 다른 종목의 정산과 같은 계정을 반대 순서로 잠그는 교착을 피한다.
     */
    private void settle(Market market, Order order, MatchResult result, OrderReceipt receipt, long reservePerShare) {
        String teamId = order.teamId();
        Map<String, long[]> points = new TreeMap<>(); // {available, locked}
        Map<String, long[]> shares = new TreeMap<>(); // {available, locked, boughtQty, boughtCost}
        Set<Long> counterparts = new LinkedHashSet<>();

        for (Trade t : result.trades()) {
            long q = t.quantity();
            // 대기 중이던 매수 주문은 자기 지정가에 체결되므로 돌려줄 차액이 없다
            long buyerReserve = t.buyOrderId() == order.id() ? reservePerShare : t.price();
            add(points, t.buyerId(), (buyerReserve - t.price()) * q, -buyerReserve * q);
            add(shares, t.buyerId(), q, 0, q, t.price() * q);
            add(shares, t.sellerId(), 0, -q, 0, 0);
            add(points, t.sellerId(), t.price() * q, 0);
            trades.insert(t);
            counterparts.add(t.buyOrderId() == order.id() ? t.sellOrderId() : t.buyOrderId());
        }
        long canceled = result.canceledQuantity();
        if (canceled > 0) {
            if (order.side() == Side.BUY) add(points, order.accountId(), reservePerShare * canceled, -reservePerShare * canceled);
            else add(shares, order.accountId(), canceled, -canceled, 0, 0);
        }

        points.forEach((id, d) -> accounts.applyPoints(id, d[0], d[1]));
        shares.forEach((id, d) -> accounts.applyShares(id, teamId, d[0], d[1], d[2], d[3]));
        for (long id : counterparts)
            orders.fillResting(id, market.book.find(id).map(Order::remaining).orElse(0L));

        Status status = result.resting() != null ? Status.OPEN
                : receipt.filledQuantity() == order.quantity() ? Status.FILLED : Status.CANCELED;
        orders.finish(order.id(), order.remaining(), status, receipt);
    }

    private void release(String accountId, String teamId, Side side, long reservePerShare, long qty) {
        if (side == Side.BUY) accounts.applyPoints(accountId, reservePerShare * qty, -reservePerShare * qty);
        else accounts.applyShares(accountId, teamId, qty, -qty, 0, 0);
    }

    // ---------- 복구 ----------

    private void cancelPending(StoredOrder o) {
        tx.executeWithoutResult(s -> {
            if (orders.cancel(o.id(), Status.PENDING))
                release(o.accountId(), o.teamId(), o.side(), o.reservePerShare(), o.quantity());
        });
        log.warn("canceled order {} left pending before settlement", o.id());
    }

    private void recoverFailedSettlement(Market market, long orderId) {
        try {
            orders.find(orderId).filter(o -> o.status() == Status.PENDING).ifPresent(this::cancelPending);
            rebuildBook(market);
        } catch (RuntimeException e) {
            market.halted = true;
            log.error("could not recover market {}, halting it", market.team.id(), e);
        }
    }

    private void rebuildBook(Market market) {
        market.resetBook();
        for (StoredOrder o : orders.byStatus(market.team.id(), Status.OPEN)) market.book.restore(o.toRestingOrder());
    }

    private static OrderReceipt replay(StoredOrder stored, String teamId, Side side, OrderType type, long price,
                                       long quantity) {
        if (!stored.sameRequest(teamId, side, type, price, quantity))
            throw new IdempotencyConflictException("이미 다른 주문에 쓴 주문 번호예요");
        return stored.receipt();
    }

    // ---------- 조회 ----------

    public List<Quote> quotes() {
        return markets.values().stream().map(m -> locked(m, Market::quote)).toList();
    }

    public Quote quote(String teamId) {
        return locked(market(teamId), Market::quote);
    }

    public OrderBookSnapshot orderBook(String teamId, int depth) {
        return locked(market(teamId), m -> new OrderBookSnapshot(
                m.book.levels(Side.SELL, depth), m.book.levels(Side.BUY, depth), m.lastPrice()));
    }

    public List<Trade> trades(String teamId, int limit) {
        return locked(market(teamId), m -> m.recentTrades(limit));
    }

    public List<StoredOrder> openOrders(String accountId) {
        return orders.openOrders(accountId);
    }

    public Account account(String accountId) {
        return accounts.find(accountId).orElseThrow(() -> new NotFoundException("계정을 찾을 수 없어요"));
    }

    public List<Holding> holdings(String accountId) {
        return accounts.holdings(accountId);
    }

    public long totalValue(Account account) {
        long value = account.availablePoints() + account.lockedPoints();
        for (Holding h : accounts.holdings(account.id())) value += h.total() * quote(h.teamId()).lastPrice();
        return value;
    }

    public List<Account> allAccounts() {
        return accounts.all();
    }

    public List<Alert> alerts(int limit) {
        return alerts.stream().limit(limit).toList();
    }

    private void recordAlerts(List<Alert> found) {
        for (Alert a : found) {
            alerts.addFirst(a);
            if (alerts.size() > MAX_ALERTS) alerts.pollLast();
        }
    }

    private Market market(String teamId) {
        Market m = markets.get(teamId);
        if (m == null) throw new NotFoundException("없는 구단이에요");
        return m;
    }

    private static void add(Map<String, long[]> deltas, String key, long... values) {
        long[] d = deltas.computeIfAbsent(key, k -> new long[values.length]);
        for (int i = 0; i < values.length; i++) d[i] += values[i];
    }

    private static <T> T locked(Market m, Function<Market, T> read) {
        m.lock.lock();
        try {
            return read.apply(m);
        } finally {
            m.lock.unlock();
        }
    }

    public record OrderBookSnapshot(List<Level> asks, List<Level> bids, long lastPrice) {
    }
}
