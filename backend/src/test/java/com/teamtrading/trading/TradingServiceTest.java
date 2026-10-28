package com.teamtrading.trading;

import com.teamtrading.account.Account;
import com.teamtrading.account.AccountRepository;
import com.teamtrading.engine.OrderType;
import com.teamtrading.engine.Side;
import com.teamtrading.team.Team;
import com.teamtrading.team.TeamCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradingServiceTest {

    private final TeamCatalog catalog = new TeamCatalog();
    private TradingService trading;
    private Account buyer;
    private Account seller;

    @BeforeEach
    void setUp() {
        trading = new TradingService(catalog, new AccountRepository(), Clock.systemUTC());
        buyer = trading.openAccount("buyer");
        seller = trading.openAccount("seller");
        seller.grantShares("lg", 100, 18_450);
    }

    @Test
    void limitBuyLocksPointsAndCancelReleasesThem() {
        OrderReceipt r = trading.place(buyer.id(), "lg", Side.BUY, OrderType.LIMIT, 18_000, 10);

        assertThat(r.restingQuantity()).isEqualTo(10);
        assertThat(buyer.lockedPoints()).isEqualTo(180_000);
        assertThat(buyer.availablePoints()).isEqualTo(TradingService.START_POINTS - 180_000);

        trading.cancel(buyer.id(), r.orderId());
        assertThat(buyer.lockedPoints()).isZero();
        assertThat(buyer.availablePoints()).isEqualTo(TradingService.START_POINTS);
        assertThat(trading.openOrders(buyer.id())).isEmpty();
    }

    @Test
    void buyerPaysRestingPriceAndGetsDifferenceBack() {
        trading.place(seller.id(), "lg", Side.SELL, OrderType.LIMIT, 18_400, 10);

        OrderReceipt r = trading.place(buyer.id(), "lg", Side.BUY, OrderType.LIMIT, 18_500, 10);

        assertThat(r.filledQuantity()).isEqualTo(10);
        assertThat(buyer.availablePoints()).isEqualTo(TradingService.START_POINTS - 184_000);
        assertThat(buyer.lockedPoints()).isZero();
        assertThat(buyer.totalShares("lg")).isEqualTo(10);
        assertThat(seller.availablePoints()).isEqualTo(TradingService.START_POINTS + 184_000);
        assertThat(seller.totalShares("lg")).isEqualTo(90);
        assertThat(trading.quote("lg").lastPrice()).isEqualTo(18_400);
    }

    @Test
    void marketBuyRefundsUnusedReserve() {
        trading.place(seller.id(), "lg", Side.SELL, OrderType.LIMIT, 18_460, 3);

        OrderReceipt r = trading.place(buyer.id(), "lg", Side.BUY, OrderType.MARKET, 0, 5);

        assertThat(r.filledQuantity()).isEqualTo(3);
        assertThat(r.canceledQuantity()).isEqualTo(2);
        assertThat(buyer.lockedPoints()).isZero();
        assertThat(buyer.availablePoints()).isEqualTo(TradingService.START_POINTS - 3 * 18_460);
    }

    @Test
    void rejectsOrdersWithoutFundsSharesOrInsideBand() {
        assertThatThrownBy(() -> trading.place(buyer.id(), "lg", Side.SELL, OrderType.LIMIT, 18_450, 1))
                .isInstanceOf(TradingException.class).hasMessageContaining("판매 가능 수량");
        assertThatThrownBy(() -> trading.place(buyer.id(), "lg", Side.BUY, OrderType.LIMIT, 18_450, 1_000))
                .isInstanceOf(TradingException.class).hasMessageContaining("포인트가 부족");
        assertThatThrownBy(() -> trading.place(buyer.id(), "lg", Side.BUY, OrderType.LIMIT, 30_000, 1))
                .isInstanceOf(TradingException.class).hasMessageContaining("상한가");
    }

    @Test
    void cannotCancelSomeoneElsesOrder() {
        OrderReceipt r = trading.place(seller.id(), "lg", Side.SELL, OrderType.LIMIT, 18_500, 1);
        assertThatThrownBy(() -> trading.cancel(buyer.id(), r.orderId())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void retryWithSameClientOrderIdReturnsFirstReceipt() {
        OrderReceipt first = trading.placeOnce(buyer.id(), "c-1", "lg", Side.BUY, OrderType.LIMIT, 18_000, 10);
        OrderReceipt retry = trading.placeOnce(buyer.id(), "c-1", "lg", Side.BUY, OrderType.LIMIT, 18_000, 10);

        assertThat(retry).isEqualTo(first);
        assertThat(trading.openOrders(buyer.id())).hasSize(1);
        assertThat(buyer.lockedPoints()).isEqualTo(180_000);
    }

    @Test
    void sameClientOrderIdForDifferentOrderIsConflict() {
        trading.placeOnce(buyer.id(), "c-1", "lg", Side.BUY, OrderType.LIMIT, 18_000, 10);

        assertThatThrownBy(() -> trading.placeOnce(buyer.id(), "c-1", "lg", Side.BUY, OrderType.LIMIT, 18_000, 11))
                .isInstanceOf(IdempotencyConflictException.class);
        // 다른 계정은 같은 번호를 써도 별개 주문이다
        trading.placeOnce(seller.id(), "c-1", "lg", Side.SELL, OrderType.LIMIT, 18_500, 1);
        assertThat(trading.openOrders(seller.id())).hasSize(1);
    }

    @Test
    void rejectedOrderCanBeRetriedWithSameClientOrderId() {
        assertThatThrownBy(() -> trading.placeOnce(buyer.id(), "c-1", "lg", Side.SELL, OrderType.LIMIT, 18_450, 1))
                .isInstanceOf(TradingException.class);

        buyer.grantShares("lg", 1, 18_450);
        OrderReceipt r = trading.placeOnce(buyer.id(), "c-1", "lg", Side.SELL, OrderType.LIMIT, 18_450, 1);
        assertThat(r.restingQuantity()).isEqualTo(1);
    }

    @Test
    void concurrentRetriesPlaceOrderOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<OrderReceipt>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++)
            calls.add(pool.submit(() -> trading.placeOnce(buyer.id(), "same", "lg", Side.BUY, OrderType.LIMIT, 18_000, 5)));
        List<Long> orderIds = new ArrayList<>();
        for (Future<OrderReceipt> f : calls) orderIds.add(f.get(10, TimeUnit.SECONDS).orderId());
        pool.shutdown();

        assertThat(orderIds).containsOnly(orderIds.getFirst());
        assertThat(trading.openOrders(buyer.id())).hasSize(1);
        assertThat(buyer.lockedPoints()).isEqualTo(90_000);
    }

    /** 여러 스레드가 동시에 주문·취소해도 포인트와 주식 총량이 보존되고 호가가 교차하지 않아야 한다 */
    @Test
    void concurrentTradingConservesPointsAndShares() throws Exception {
        List<Account> accounts = new ArrayList<>(List.of(buyer, seller));
        for (int i = 0; i < 10; i++) accounts.add(trading.openAccount("u" + i));
        List<Team> teams = catalog.all().subList(0, 3);
        for (Account a : accounts) for (Team t : teams) a.grantShares(t.id(), 200, t.basePrice());
        long pointsBefore = totalPoints(accounts);
        var sharesBefore = teams.stream().map(t -> totalShares(accounts, t)).toList();

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> jobs = new ArrayList<>();
        for (int w = 0; w < 8; w++) {
            long seed = w;
            jobs.add(pool.submit(() -> randomTrading(new Random(seed), accounts, teams, 2_000)));
        }
        for (Future<?> f : jobs) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(totalPoints(accounts)).isEqualTo(pointsBefore);
        for (int i = 0; i < teams.size(); i++) {
            Team t = teams.get(i);
            assertThat(totalShares(accounts, t)).isEqualTo(sharesBefore.get(i));
            var book = trading.orderBook(t.id(), 1);
            if (!book.asks().isEmpty() && !book.bids().isEmpty())
                assertThat(book.bids().getFirst().price()).isLessThan(book.asks().getFirst().price());
        }
        assertThat(trading.trades(teams.getFirst().id(), 1)).isNotEmpty();
    }

    private void randomTrading(Random rnd, List<Account> accounts, List<Team> teams, int steps) {
        for (int i = 0; i < steps; i++) {
            Account a = accounts.get(rnd.nextInt(accounts.size()));
            Team t = teams.get(rnd.nextInt(teams.size()));
            try {
                int roll = rnd.nextInt(10);
                if (roll == 0) {
                    var mine = trading.openOrders(a.id());
                    if (!mine.isEmpty()) trading.cancel(a.id(), mine.getFirst().id());
                } else {
                    Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
                    OrderType type = roll == 1 ? OrderType.MARKET : OrderType.LIMIT;
                    long price = t.basePrice() + (rnd.nextInt(11) - 5) * 10L;
                    trading.place(a.id(), t.id(), side, type, price, 1 + rnd.nextInt(10));
                }
            } catch (TradingException | NotFoundException expected) {
                // 잔고 부족이나 이미 체결된 주문 취소는 정상적인 거절이다
            }
        }
    }

    private static long totalShares(List<Account> accounts, Team team) {
        return accounts.stream().mapToLong(a -> a.totalShares(team.id())).sum();
    }

    private static long totalPoints(List<Account> accounts) {
        return accounts.stream().mapToLong(a -> a.availablePoints() + a.lockedPoints()).sum();
    }
}
