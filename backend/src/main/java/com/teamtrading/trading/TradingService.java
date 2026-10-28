package com.teamtrading.trading;

import com.teamtrading.account.Account;
import com.teamtrading.account.AccountRepository;
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
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 주문 접수부터 체결, 정산까지. 종목마다 락을 하나 두어 같은 종목의 주문은 한 줄로 처리하고
 * 다른 종목끼리는 동시에 처리한다. 계정 잔고 변경은 Account 안에서 각각 원자적이라
 * 두 계정의 락을 동시에 잡는 일이 없고 교착 상태가 생기지 않는다.
 */
@Service
public class TradingService {

    public static final long START_POINTS = 1_000_000;
    private static final int MAX_ALERTS = 200;

    private final AccountRepository accounts;
    private final Clock clock;
    private final Map<String, Market> markets = new LinkedHashMap<>();
    private final Map<Long, String> orderTeams = new ConcurrentHashMap<>();
    private final Deque<Alert> alerts = new ConcurrentLinkedDeque<>();
    private final AtomicLong ids = new AtomicLong();
    // 첫 버전은 메모리에 둔다. DB 단계에서 orders 테이블의 UNIQUE (account_id, client_order_id)로 옮긴다
    private final Map<String, IdempotentOrder> placedOrders = new ConcurrentHashMap<>();

    private record OrderRequest(String teamId, Side side, OrderType type, long price, long quantity) {
    }

    private record IdempotentOrder(OrderRequest request, CompletableFuture<OrderReceipt> receipt) {
    }

    public TradingService(TeamCatalog catalog, AccountRepository accounts, Clock clock) {
        this.accounts = accounts;
        this.clock = clock;
        for (Team team : catalog.all()) markets.put(team.id(), new Market(team));
    }

    public Account openAccount(String nickname) {
        return accounts.save(new Account(UUID.randomUUID().toString(), nickname, START_POINTS));
    }

    /**
     * 클라이언트가 정한 주문 번호로 한 번만 접수한다. 응답을 못 받아 같은 번호로 다시 보내면
     * 주문을 새로 넣지 않고 처음 결과를 그대로 돌려준다. 동시에 들어온 재시도는 첫 요청이 끝날 때까지 기다린다.
     * 접수 전에 거절된 주문은 기록하지 않아 같은 번호로 고쳐서 다시 보낼 수 있다.
     */
    public OrderReceipt placeOnce(String accountId, String clientOrderId, String teamId, Side side, OrderType type,
                                  long price, long quantity) {
        OrderRequest request = new OrderRequest(teamId, side, type, type == OrderType.MARKET ? 0 : price, quantity);
        String key = accountId + ":" + clientOrderId;
        IdempotentOrder mine = new IdempotentOrder(request, new CompletableFuture<>());
        IdempotentOrder existing = placedOrders.putIfAbsent(key, mine);
        if (existing != null) {
            if (!existing.request().equals(request))
                throw new IdempotencyConflictException("이미 다른 주문에 쓴 주문 번호예요");
            try {
                return existing.receipt().join();
            } catch (CompletionException e) {
                throw (RuntimeException) e.getCause();
            }
        }
        try {
            OrderReceipt receipt = place(accountId, teamId, side, type, price, quantity);
            mine.receipt().complete(receipt);
            return receipt;
        } catch (RuntimeException e) {
            placedOrders.remove(key, mine);
            mine.receipt().completeExceptionally(e);
            throw e;
        }
    }

    public OrderReceipt place(String accountId, String teamId, Side side, OrderType type, long price, long quantity) {
        Market market = market(teamId);
        Account account = account(accountId);
        long base = market.team.basePrice();
        PriceRules.validate(type, price, quantity, base).ifPresent(reason -> {
            throw new TradingException(reason);
        });
        // 시장가 매수는 상한가 기준으로 포인트를 묶어두고 체결 후 차액을 돌려준다
        long reservePerShare = type == OrderType.LIMIT ? price : PriceRules.upperLimit(base);

        market.lock.lock();
        try {
            if (side == Side.BUY) account.lockPoints(reservePerShare * quantity);
            else account.lockShares(teamId, quantity);

            Instant now = clock.instant();
            Order order = new Order(ids.incrementAndGet(), accountId, teamId, side, type, price, quantity, now);
            MatchResult result = market.book.submit(order, ids::incrementAndGet, now);

            for (Trade trade : result.trades()) {
                // 대기 중이던 매수 주문은 자기 지정가에 체결되므로 돌려줄 차액이 없다
                long buyerReserve = trade.buyOrderId() == order.id() ? reservePerShare : trade.price();
                account(trade.buyerId()).settleBuy(teamId, trade.quantity(), trade.price(), buyerReserve);
                account(trade.sellerId()).settleSell(teamId, trade.quantity(), trade.price());
                recordAlerts(FdsDetector.detect(trade, market.lastPrice(), market.recentTrades(Integer.MAX_VALUE)));
                market.record(trade);
                if (trade.buyOrderId() != order.id()) forgetIfDone(market, trade.buyOrderId());
                if (trade.sellOrderId() != order.id()) forgetIfDone(market, trade.sellOrderId());
            }

            long canceled = result.canceledQuantity();
            if (canceled > 0) {
                if (side == Side.BUY) account.unlockPoints(reservePerShare * canceled);
                else account.unlockShares(teamId, canceled);
            }
            if (result.resting() != null) orderTeams.put(order.id(), teamId);

            long resting = result.resting() == null ? 0 : result.resting().remaining();
            return new OrderReceipt(order.id(), result.filledQuantity(), resting, canceled,
                    result.selfTradePrevented(), message(type, quantity, result, resting));
        } finally {
            market.lock.unlock();
        }
    }

    public void cancel(String accountId, long orderId) {
        String teamId = orderTeams.get(orderId);
        if (teamId == null) throw new NotFoundException("대기 중인 주문이 아니에요");
        Market market = market(teamId);
        market.lock.lock();
        try {
            Order order = market.book.find(orderId)
                    .filter(o -> o.accountId().equals(accountId))
                    .orElseThrow(() -> new NotFoundException("대기 중인 주문이 아니에요"));
            market.book.cancel(orderId);
            orderTeams.remove(orderId);
            if (order.side() == Side.BUY) account(accountId).unlockPoints(order.price() * order.remaining());
            else account(accountId).unlockShares(teamId, order.remaining());
        } finally {
            market.lock.unlock();
        }
    }

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

    public List<Order> openOrders(String accountId) {
        List<Order> out = new ArrayList<>();
        for (Market m : markets.values()) {
            out.addAll(locked(m, mk -> mk.book.openOrders().stream()
                    .filter(o -> o.accountId().equals(accountId)).toList()));
        }
        out.sort(Comparator.comparing(Order::createdAt).reversed());
        return out;
    }

    public Account account(String accountId) {
        return accounts.find(accountId).orElseThrow(() -> new NotFoundException("계정을 찾을 수 없어요"));
    }

    public long totalValue(Account account) {
        long value = account.availablePoints() + account.lockedPoints();
        for (var h : account.holdings()) value += h.total() * quote(h.teamId()).lastPrice();
        return value;
    }

    public List<Account> allAccounts() {
        return List.copyOf(accounts.all());
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

    private void forgetIfDone(Market market, long orderId) {
        if (market.book.find(orderId).isEmpty()) orderTeams.remove(orderId);
    }

    private Market market(String teamId) {
        Market m = markets.get(teamId);
        if (m == null) throw new NotFoundException("없는 구단이에요");
        return m;
    }

    private static <T> T locked(Market m, Function<Market, T> read) {
        m.lock.lock();
        try {
            return read.apply(m);
        } finally {
            m.lock.unlock();
        }
    }

    private static String message(OrderType type, long qty, MatchResult r, long resting) {
        long filled = r.filledQuantity();
        if (r.selfTradePrevented())
            return "내 반대 주문과 체결될 수 없어 %d주를 취소했어요 (자기매매 방지)".formatted(r.canceledQuantity());
        if (type == OrderType.MARKET && r.canceledQuantity() > 0)
            return filled > 0 ? "%d주 체결, 남은 %d주는 취소됐어요".formatted(filled, r.canceledQuantity())
                    : "체결할 호가가 없어 취소됐어요";
        if (filled == qty) return "%d주 모두 체결됐어요".formatted(qty);
        if (filled > 0) return "%d주 체결, %d주는 대기 중이에요".formatted(filled, resting);
        return "주문이 접수됐어요. 호가창에서 대기 중이에요";
    }

    public record OrderBookSnapshot(List<Level> asks, List<Level> bids, long lastPrice) {
    }
}
