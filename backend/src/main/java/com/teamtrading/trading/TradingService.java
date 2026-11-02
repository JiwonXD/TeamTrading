package com.teamtrading.trading;

import com.teamtrading.account.Account;
import com.teamtrading.account.AccountStore;
import com.teamtrading.account.Holding;
import com.teamtrading.engine.Level;
import com.teamtrading.engine.MatchResult;
import com.teamtrading.engine.Order;
import com.teamtrading.engine.OrderBook;
import com.teamtrading.engine.OrderType;
import com.teamtrading.engine.PriceRules;
import com.teamtrading.engine.Side;
import com.teamtrading.engine.Trade;
import com.teamtrading.fds.Alert;
import com.teamtrading.fds.FdsDetector;
import com.teamtrading.ledger.Journal;
import com.teamtrading.ledger.Ledger;
import com.teamtrading.points.PointIssuer;
import com.teamtrading.points.PointSource;
import com.teamtrading.team.Team;
import com.teamtrading.team.TeamCatalog;
import com.teamtrading.trading.StoredOrder.Status;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

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
 *
 * <p>잔고를 바꾸는 트랜잭션은 같은 이동을 원장에도 전표로 남긴다. 잔고 갱신과 전표는 따로 계산하므로
 * 대사({@link com.teamtrading.ledger.Reconciliation})로 둘이 맞는지 확인할 수 있다.
 *
 * <p>호가창이 바뀔 때마다 종목 락 안에서 {@link MarketUpdate}를 발행한다. 락 안에서 번호를 매기고 보내므로
 * 같은 종목의 업데이트는 번호 순서대로 나간다.
 */
@Service
public class TradingService {

    public static final long START_POINTS = PointSource.SIGNUP.amount();
    private static final int MAX_ALERTS = 200;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter LISTING_TIME =
            DateTimeFormatter.ofPattern("M월 d일 HH:mm").withZone(ZoneId.of("Asia/Seoul"));
    private static final Logger log = LoggerFactory.getLogger(TradingService.class);

    private final AccountStore accounts;
    private final ListingStore listings;
    private final OrderStore orders;
    private final TradeStore trades;
    private final Ledger ledger;
    private final PointIssuer points;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final long epoch;
    private final Map<String, Market> markets = new LinkedHashMap<>();
    private final Deque<Alert> alerts = new ConcurrentLinkedDeque<>();
    private final AtomicLong ids = new AtomicLong();

    public TradingService(TeamCatalog catalog, AccountStore accounts, OrderStore orders, TradeStore trades,
                          Ledger ledger, PointIssuer points, TransactionTemplate tx, Clock clock,
                          ApplicationEventPublisher events, ListingStore listings) {
        this.accounts = accounts;
        this.listings = listings;
        this.orders = orders;
        this.trades = trades;
        this.ledger = ledger;
        this.points = points;
        this.tx = tx;
        this.clock = clock;
        this.events = events;
        this.epoch = clock.millis();
        for (Team team : catalog.all()) markets.put(team.id(), new Market(team));
    }

    /** 기동 시 DB를 기준으로 호가창과 시세를 다시 만든다 */
    @PostConstruct
    public void recover() {
        ids.set(Math.max(orders.maxId(), trades.maxId()));
        Map<String, Instant> holds = listings.holds();
        Map<String, Long> closes = trades.latestCloses();
        Instant dayStart = dayStart();
        for (Market m : markets.values()) {
            m.listingAt = holds.get(m.team.id());
            for (StoredOrder pending : orders.byStatus(m.team.id(), Status.PENDING)) cancelPending(pending);
            rebuildBook(m);
            m.restoreTrades(trades.recent(m.team.id(), Market.RECENT_TRADES));
            m.startDay(closes.getOrDefault(m.team.id(), m.team.basePrice()), trades.stats(m.team.id(), dayStart));
        }
    }

    private Instant dayStart() {
        return clock.instant().atZone(KST).toLocalDate().atStartOfDay(KST).toInstant();
    }

    /**
     * 일 마감 뒤 새 날을 연다. 기준가를 가장 최근 종가로 바꾸고 당일 시세를 새로 센다.
     * 새 가격제한폭을 벗어난 대기 주문은 취소해 묶인 포인트·주식을 돌려준다. 그대로 두면 제한폭 밖 가격에 체결될 수 있다.
     * 종목 락 안에서 바꾸므로 주문 접수는 바뀌기 전이나 후의 기준가 하나만 본다.
     *
     * @return 취소한 주문 수
     */
    public int startNewDay() {
        Map<String, Long> closes = trades.latestCloses();
        Instant dayStart = dayStart();
        int canceled = 0;
        for (Market market : markets.values()) {
            market.lock.lock();
            try {
                long base = closes.getOrDefault(market.team.id(), market.team.basePrice());
                long upper = PriceRules.upperLimit(base);
                long lower = PriceRules.lowerLimit(base);
                for (StoredOrder o : orders.byStatus(market.team.id(), Status.OPEN)) {
                    if (o.price() >= lower && o.price() <= upper) continue;
                    long remaining = market.book.find(o.id()).map(Order::remaining).orElse(0L);
                    tx.executeWithoutResult(s -> {
                        if (orders.cancel(o.id(), Status.OPEN)) release(o, remaining);
                    });
                    market.book.cancel(o.id());
                    canceled++;
                }
                market.startDay(base, trades.stats(market.team.id(), dayStart));
                events.publishEvent(market.nextUpdate(epoch, List.of()));
            } catch (RuntimeException e) {
                log.error("could not start a new day for {}, halting it", market.team.id(), e);
                market.halted = true;
            } finally {
                market.lock.unlock();
            }
        }
        if (canceled > 0) log.info("canceled {} resting orders outside the new price limits", canceled);
        return canceled;
    }

    public Account openAccount(String nickname) {
        return tx.execute(s -> {
            String id = UUID.randomUUID().toString();
            accounts.create(id, nickname, clock.instant());
            points.grantSignup(id);
            return accounts.find(id).orElseThrow();
        });
    }

    /**
     * 첫 상장 전 거래를 막는다. until이 null이면 막지 않는다. 공모 배정 전에는 Instant.MAX로 둔다.
     * 주문 접수와 같은 종목 락 안에서 바꾸므로, 바뀐 뒤 접수되는 주문은 모두 새 값을 본다
     */
    public void holdUntil(String teamId, Instant until) {
        underMarketLock(teamId, () -> {
            Market market = market(teamId);
            market.listingAt = until;
            if (auctionPending(market)) market.book.startCollecting();
            return null;
        });
    }

    /** 상장 시각이 된 구단의 동시호가를 체결한다 */
    public void openDueAuctions() {
        for (Market market : markets.values()) {
            market.lock.lock();
            try {
                if (market.book.isCollecting() && !before(market.listingAt)) openAuction(market);
            } catch (TradingException e) {
                // openAuction이 이미 기록했다. 다음 차례에 다시 시도한다
            } finally {
                market.lock.unlock();
            }
        }
    }

    /** 배정이 끝나 상장 시각을 기다리는 중(동시호가 대상)인지 */
    private static boolean auctionPending(Market market) {
        return market.listingAt != null && !market.listingAt.equals(Instant.MAX);
    }

    /** 지금이 그 시각 전인지. null이면 이미 지난 것으로 본다 */
    private boolean before(Instant at) {
        return at != null && clock.instant().isBefore(at);
    }

    /**
     * 종목 락 안에서 실행한다. 공모 배정처럼 체결 밖에서 보유 수량을 바꾸는 일은 이 락 안에서 해야
     * 평균 단가 계산이 체결 정산과 섞이지 않는다
     */
    public <T> T underMarketLock(String teamId, Supplier<T> work) {
        Market market = market(teamId);
        market.lock.lock();
        try {
            return work.get();
        } finally {
            market.lock.unlock();
        }
    }

    /** 시즌 시작 시 지급하는 초기 보유분. 거래 중에는 쓰지 않는다 */
    public void grantShares(String accountId, String teamId, long qty, long price) {
        Market market = market(teamId);
        market.lock.lock();
        try {
            tx.executeWithoutResult(s -> {
                accounts.applyShares(accountId, teamId, qty, 0, qty, qty * price);
                accounts.addInitialValue(accountId, qty * price);
                ledger.post(Journal.grant(accountId, teamId, qty), clock.instant());
            });
        } finally {
            market.lock.unlock();
        }
    }

    /**
     * 클라이언트가 정한 주문 번호(clientOrderId)로 한 번만 접수한다. 응답을 못 받아 같은 번호로 다시 보내면
     * 주문을 새로 넣지 않고 처음 결과를 돌려준다. 접수 전에 거절된 주문은 저장하지 않아 같은 번호로 다시 보낼 수 있다.
     */
    public OrderReceipt place(String accountId, String clientOrderId, String teamId, Side side, OrderType type,
                              long price, long quantity) {
        Market market = market(teamId);
        account(accountId);

        market.lock.lock();
        try {
            if (market.halted) throw new TradingException("이 구단은 점검 중이라 잠시 거래할 수 없어요");
            // 기준가는 일 마감 뒤 바뀌므로 종목 락 안에서 확인한다
            long base = market.basePrice();
            PriceRules.validate(type, price, quantity, base).ifPresent(reason -> {
                throw new TradingException(reason);
            });
            // 시장가 매수는 상한가 기준으로 포인트를 묶어두고 체결 후 차액을 돌려준다
            long reservePerShare = type == OrderType.LIMIT ? price : PriceRules.upperLimit(base);
            Instant listingAt = market.listingAt;
            if (Instant.MAX.equals(listingAt)) throw new TradingException("공모 청약 중인 구단이라 아직 거래할 수 없어요");
            if (market.book.isCollecting()) {
                if (!before(listingAt)) {
                    openAuction(market);
                } else {
                    // 상장 전 동시호가: 지정가 주문을 모아 상장 시각에 한 가격으로 체결한다
                    if (type != OrderType.LIMIT)
                        throw new TradingException("상장 전 동시호가 시간이라 지정가 주문만 받아요. "
                                + LISTING_TIME.format(listingAt) + "에 한 가격으로 체결해요");
                    if (market.book.hasOrderFrom(accountId, side.opposite()))
                        throw new TradingException("동시호가 시간에는 같은 구단을 사고 팔 수 없어요. 반대 주문을 먼저 취소해 주세요");
                }
            }
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
            events.publishEvent(market.nextUpdate(epoch, result.trades().reversed()));
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
                release(stored, order.remaining());
            });
            market.book.cancel(orderId);
            events.publishEvent(market.nextUpdate(epoch, List.of()));
        } finally {
            market.lock.unlock();
        }
    }

    // ---------- 트랜잭션 본문 ----------

    private void accept(Order order, String clientOrderId, long reservePerShare) {
        boolean buy = order.side() == Side.BUY;
        long amount = buy ? reservePerShare * order.quantity() : order.quantity();
        boolean locked = buy
                ? accounts.lockPoints(order.accountId(), amount)
                : accounts.lockShares(order.accountId(), order.teamId(), amount);
        if (!locked) throw new TradingException(buy ? "주문 가능 포인트가 부족해요" : "판매 가능 수량이 부족해요");
        orders.insertPending(order, clientOrderId, reservePerShare);
        ledger.post(Journal.lock(order.id(), order.accountId(), buy ? Journal.POINT : order.teamId(), amount),
                order.createdAt());
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
            ledger.post(Journal.trade(t, buyerReserve), t.executedAt());
            counterparts.add(t.buyOrderId() == order.id() ? t.sellOrderId() : t.buyOrderId());
        }
        long canceled = result.canceledQuantity();
        if (canceled > 0) {
            boolean buy = order.side() == Side.BUY;
            long amount = buy ? reservePerShare * canceled : canceled;
            if (buy) add(points, order.accountId(), amount, -amount);
            else add(shares, order.accountId(), amount, -amount, 0, 0);
            ledger.post(Journal.release(order.id(), order.accountId(), buy ? Journal.POINT : teamId, amount),
                    clock.instant());
        }

        points.forEach((id, d) -> accounts.applyPoints(id, d[0], d[1]));
        shares.forEach((id, d) -> accounts.applyShares(id, teamId, d[0], d[1], d[2], d[3]));
        for (long id : counterparts)
            orders.fillResting(id, market.book.find(id).map(Order::remaining).orElse(0L));

        Status status = result.resting() != null ? Status.OPEN
                : receipt.filledQuantity() == order.quantity() ? Status.FILLED : Status.CANCELED;
        orders.finish(order.id(), order.remaining(), status, receipt);
    }

    /**
     * 동시호가 체결. 모인 주문을 한 가격에 체결하고 연속 매매로 넘어간다. 매수자는 자기 지정가로 묶어둔 포인트에서
     * 체결가와의 차액을 돌려받는다. 정산이 실패하면 DB 기준으로 동시호가 호가창을 되살리고 다음에 다시 시도한다
     */
    private void openAuction(Market market) {
        OrderBook.AuctionResult result;
        try {
            result = market.book.uncross(ids::incrementAndGet, clock.instant(), market.basePrice());
            if (!result.trades().isEmpty()) tx.executeWithoutResult(s -> settleAuction(market.team.id(), result));
        } catch (RuntimeException e) {
            log.error("opening auction failed for {}", market.team.id(), e);
            try {
                rebuildBook(market);
            } catch (RuntimeException again) {
                market.halted = true;
                log.error("could not recover market {}, halting it", market.team.id(), again);
            }
            throw new TradingException("상장 체결을 처리하지 못했어요. 잠시 후 다시 시도해 주세요");
        }
        market.listingAt = null;
        for (Trade trade : result.trades()) {
            // 시초가는 기준가와 크게 다를 수 있어 가격 괴리는 체결가 기준으로 본다
            recordAlerts(FdsDetector.detect(trade, result.price(), market.recentTrades(Integer.MAX_VALUE)));
            market.record(trade);
        }
        events.publishEvent(market.nextUpdate(epoch, result.trades().reversed()));
        log.info("opening auction for {} matched {} trades at {}", market.team.id(), result.trades().size(),
                result.price());
    }

    private void settleAuction(String teamId, OrderBook.AuctionResult result) {
        Map<Long, Long> limitPrice = new HashMap<>();
        for (Order o : result.touched()) limitPrice.put(o.id(), o.price());
        Map<String, long[]> points = new TreeMap<>(); // {available, locked}
        Map<String, long[]> shares = new TreeMap<>(); // {available, locked, boughtQty, boughtCost}
        for (Trade t : result.trades()) {
            long q = t.quantity();
            long buyerReserve = limitPrice.get(t.buyOrderId());
            add(points, t.buyerId(), (buyerReserve - t.price()) * q, -buyerReserve * q);
            add(shares, t.buyerId(), q, 0, q, t.price() * q);
            add(shares, t.sellerId(), 0, -q, 0, 0);
            add(points, t.sellerId(), t.price() * q, 0);
            trades.insert(t);
            ledger.post(Journal.trade(t, buyerReserve), t.executedAt());
        }
        points.forEach((id, d) -> accounts.applyPoints(id, d[0], d[1]));
        shares.forEach((id, d) -> accounts.applyShares(id, teamId, d[0], d[1], d[2], d[3]));
        for (Order o : result.touched()) orders.fillResting(o.id(), o.remaining());
    }

    private void release(StoredOrder o, long qty) {
        boolean buy = o.side() == Side.BUY;
        long amount = buy ? o.reservePerShare() * qty : qty;
        if (buy) accounts.applyPoints(o.accountId(), amount, -amount);
        else accounts.applyShares(o.accountId(), o.teamId(), amount, -amount, 0, 0);
        ledger.post(Journal.release(o.id(), o.accountId(), buy ? Journal.POINT : o.teamId(), amount), clock.instant());
    }

    // ---------- 복구 ----------

    private void cancelPending(StoredOrder o) {
        tx.executeWithoutResult(s -> {
            if (orders.cancel(o.id(), Status.PENDING))
                release(o, o.quantity());
        });
        log.warn("canceled order {} left pending before settlement", o.id());
    }

    private void recoverFailedSettlement(Market market, long orderId) {
        try {
            orders.find(orderId).filter(o -> o.status() == Status.PENDING).ifPresent(this::cancelPending);
            rebuildBook(market);
            events.publishEvent(market.nextUpdate(epoch, List.of()));
        } catch (RuntimeException e) {
            market.halted = true;
            log.error("could not recover market {}, halting it", market.team.id(), e);
        }
    }

    private void rebuildBook(Market market) {
        market.resetBook();
        if (auctionPending(market)) market.book.startCollecting();
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
                m.book.levels(Side.SELL, depth), m.book.levels(Side.BUY, depth), m.lastPrice(),
                epoch, m.seq()));
    }

    /**
     * 현재 시세를 종목 락 안에서 넘긴다. 실시간 구독 등록을 onSnapshot 안에서 하면
     * 스냅숏과 그 뒤 첫 업데이트 사이에 다른 업데이트가 끼거나 빠지지 않는다.
     */
    public void subscribe(String teamId, int recentTrades, Consumer<MarketUpdate> onSnapshot) {
        Market market = market(teamId);
        market.lock.lock();
        try {
            onSnapshot.accept(market.snapshot(epoch, market.recentTrades(recentTrades)));
        } finally {
            market.lock.unlock();
        }
    }

    public List<Trade> trades(String teamId, int limit) {
        return locked(market(teamId), m -> m.recentTrades(limit));
    }

    public List<Trade> myTrades(String accountId, int limit) {
        return trades.byAccount(accountId, limit);
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

    /** 복구에 실패해 거래를 멈춘 구단 */
    public List<String> haltedTeams() {
        return markets.values().stream().filter(m -> m.halted).map(m -> m.team.id()).toList();
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

    public record OrderBookSnapshot(List<Level> asks, List<Level> bids, long lastPrice, long epoch, long seq) {
    }
}
