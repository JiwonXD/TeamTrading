package com.teamtrading.trading;

import com.teamtrading.account.Account;
import com.teamtrading.account.AccountStore;
import com.teamtrading.engine.Order;
import com.teamtrading.engine.OrderType;
import com.teamtrading.engine.Side;
import com.teamtrading.engine.Trade;
import com.teamtrading.ledger.Journal;
import com.teamtrading.ledger.Ledger;
import com.teamtrading.ledger.Reconciliation;
import com.teamtrading.team.Team;
import com.teamtrading.team.TeamCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class TradingServiceTest {

    @Autowired TradingService trading;
    @Autowired TeamCatalog catalog;
    @Autowired AccountStore accountStore;
    @Autowired OrderStore orderStore;
    @Autowired TradeStore tradeStore;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired Ledger ledger;
    @Autowired Reconciliation reconciliation;

    private String buyer;
    private String seller;

    @BeforeEach
    void setUp() {
        buyer = trading.openAccount("buyer").id();
        seller = trading.openAccount("seller").id();
        trading.grantShares(seller, "lg", 100, 18_450);
    }

    private OrderReceipt place(String account, Side side, OrderType type, long price, long qty) {
        return trading.place(account, UUID.randomUUID().toString(), "lg", side, type, price, qty);
    }

    private Account account(String id) {
        return trading.account(id);
    }

    @Test
    void limitBuyLocksPointsAndCancelReleasesThem() {
        OrderReceipt r = place(buyer, Side.BUY, OrderType.LIMIT, 18_000, 10);

        assertThat(r.restingQuantity()).isEqualTo(10);
        assertThat(account(buyer).lockedPoints()).isEqualTo(180_000);
        assertThat(account(buyer).availablePoints()).isEqualTo(TradingService.START_POINTS - 180_000);

        trading.cancel(buyer, r.orderId());
        assertThat(account(buyer).lockedPoints()).isZero();
        assertThat(account(buyer).availablePoints()).isEqualTo(TradingService.START_POINTS);
        assertThat(trading.openOrders(buyer)).isEmpty();
        assertThat(trading.orderBook("lg", 5).bids()).isEmpty();
    }

    @Test
    void buyerPaysRestingPriceAndGetsDifferenceBack() {
        place(seller, Side.SELL, OrderType.LIMIT, 18_400, 10);

        OrderReceipt r = place(buyer, Side.BUY, OrderType.LIMIT, 18_500, 10);

        assertThat(r.filledQuantity()).isEqualTo(10);
        assertThat(account(buyer).availablePoints()).isEqualTo(TradingService.START_POINTS - 184_000);
        assertThat(account(buyer).lockedPoints()).isZero();
        assertThat(accountStore.totalShares(buyer, "lg")).isEqualTo(10);
        assertThat(accountStore.holdings(buyer).getFirst().averagePrice()).isEqualTo(18_400);
        assertThat(account(seller).availablePoints()).isEqualTo(TradingService.START_POINTS + 184_000);
        assertThat(accountStore.totalShares(seller, "lg")).isEqualTo(90);
        assertThat(trading.quote("lg").lastPrice()).isEqualTo(18_400);
        assertThat(trading.openOrders(seller)).isEmpty();
    }

    @Test
    void marketBuyRefundsUnusedReserve() {
        place(seller, Side.SELL, OrderType.LIMIT, 18_460, 3);

        OrderReceipt r = place(buyer, Side.BUY, OrderType.MARKET, 0, 5);

        assertThat(r.filledQuantity()).isEqualTo(3);
        assertThat(r.canceledQuantity()).isEqualTo(2);
        assertThat(account(buyer).lockedPoints()).isZero();
        assertThat(account(buyer).availablePoints()).isEqualTo(TradingService.START_POINTS - 3 * 18_460);
    }

    @Test
    void rejectsOrdersWithoutFundsSharesOrInsideBand() {
        assertThatThrownBy(() -> place(buyer, Side.SELL, OrderType.LIMIT, 18_450, 1))
                .isInstanceOf(TradingException.class).hasMessageContaining("판매 가능 수량");
        assertThatThrownBy(() -> place(buyer, Side.BUY, OrderType.LIMIT, 18_450, 1_000))
                .isInstanceOf(TradingException.class).hasMessageContaining("포인트가 부족");
        assertThatThrownBy(() -> place(buyer, Side.BUY, OrderType.LIMIT, 30_000, 1))
                .isInstanceOf(TradingException.class).hasMessageContaining("상한가");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Long.class)).isZero();
    }

    @Test
    void cannotCancelSomeoneElsesOrder() {
        OrderReceipt r = place(seller, Side.SELL, OrderType.LIMIT, 18_500, 1);
        assertThatThrownBy(() -> trading.cancel(buyer, r.orderId())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void retryWithSameClientOrderIdReturnsFirstReceipt() {
        OrderReceipt first = trading.place(buyer, "c-1", "lg", Side.BUY, OrderType.LIMIT, 18_000, 10);
        // 처음 주문이 나중에 일부 체결돼도 재시도 응답은 처음 결과 그대로다
        place(seller, Side.SELL, OrderType.LIMIT, 18_000, 4);
        OrderReceipt retry = trading.place(buyer, "c-1", "lg", Side.BUY, OrderType.LIMIT, 18_000, 10);

        assertThat(retry).isEqualTo(first);
        assertThat(trading.openOrders(buyer)).hasSize(1);
        assertThat(trading.openOrders(buyer).getFirst().remaining()).isEqualTo(6);
    }

    @Test
    void sameClientOrderIdForDifferentOrderIsConflict() {
        trading.place(buyer, "c-1", "lg", Side.BUY, OrderType.LIMIT, 18_000, 10);

        assertThatThrownBy(() -> trading.place(buyer, "c-1", "lg", Side.BUY, OrderType.LIMIT, 18_000, 11))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> trading.place(buyer, "c-1", "kt", Side.BUY, OrderType.LIMIT, 12_000, 10))
                .isInstanceOf(IdempotencyConflictException.class);
        // 다른 계정은 같은 번호를 써도 별개 주문이다
        trading.place(seller, "c-1", "lg", Side.SELL, OrderType.LIMIT, 18_500, 1);
        assertThat(trading.openOrders(seller)).hasSize(1);
    }

    @Test
    void rejectedOrderCanBeRetriedWithSameClientOrderId() {
        assertThatThrownBy(() -> trading.place(buyer, "c-1", "lg", Side.SELL, OrderType.LIMIT, 18_450, 1))
                .isInstanceOf(TradingException.class);

        trading.grantShares(buyer, "lg", 1, 18_450);
        OrderReceipt r = trading.place(buyer, "c-1", "lg", Side.SELL, OrderType.LIMIT, 18_450, 1);
        assertThat(r.restingQuantity()).isEqualTo(1);
    }

    @Test
    void concurrentRetriesPlaceOrderOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<OrderReceipt>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++)
            calls.add(pool.submit(() -> trading.place(buyer, "same", "lg", Side.BUY, OrderType.LIMIT, 18_000, 5)));
        List<Long> orderIds = new ArrayList<>();
        for (Future<OrderReceipt> f : calls) orderIds.add(f.get(10, TimeUnit.SECONDS).orderId());
        pool.shutdown();

        assertThat(orderIds).containsOnly(orderIds.getFirst());
        assertThat(trading.openOrders(buyer)).hasSize(1);
        assertThat(account(buyer).lockedPoints()).isEqualTo(90_000);
    }

    @Test
    void restartRebuildsBookAndQuoteFromDatabase() {
        place(seller, Side.SELL, OrderType.LIMIT, 18_500, 10);
        place(seller, Side.SELL, OrderType.LIMIT, 18_600, 5);
        place(buyer, Side.BUY, OrderType.LIMIT, 18_500, 4);
        place(buyer, Side.BUY, OrderType.LIMIT, 18_300, 7);
        var before = trading.orderBook("lg", 10);

        TradingService restarted = new TradingService(catalog, accountStore, orderStore, tradeStore, ledger, tx, Clock.systemUTC());
        restarted.recover();

        assertThat(restarted.orderBook("lg", 10)).isEqualTo(before);
        assertThat(restarted.quote("lg").lastPrice()).isEqualTo(18_500);
        assertThat(restarted.quote("lg").volume()).isEqualTo(4);
        // 복구된 호가창에서 매칭을 이어가도 같은 결과가 나온다
        OrderReceipt r = restarted.place(buyer, "after-restart", "lg", Side.BUY, OrderType.LIMIT, 18_500, 6);
        assertThat(r.filledQuantity()).isEqualTo(6);
    }

    @Test
    void pendingOrderLeftByCrashIsCanceledOnRestart() {
        // 접수 트랜잭션만 끝나고 정산 전에 서버가 죽은 상황
        Order order = new Order(9_999, buyer, "lg", Side.BUY, OrderType.LIMIT, 18_000, 10, java.time.Instant.now());
        tx.executeWithoutResult(s -> {
            accountStore.lockPoints(buyer, 180_000);
            orderStore.insertPending(order, "crashed", 18_000);
            ledger.post(Journal.lock(9_999, buyer, Journal.POINT, 180_000), order.createdAt());
        });

        TradingService restarted = new TradingService(catalog, accountStore, orderStore, tradeStore, ledger, tx, Clock.systemUTC());
        restarted.recover();

        assertThat(orderStore.find(9_999).orElseThrow().status()).isEqualTo(StoredOrder.Status.CANCELED);
        assertThat(account(buyer).lockedPoints()).isZero();
        assertThat(account(buyer).availablePoints()).isEqualTo(TradingService.START_POINTS);
        assertThat(restarted.orderBook("lg", 5).bids()).isEmpty();
        assertThat(reconciliation.run().balanced()).isTrue();
    }

    @Test
    void failedSettlementCancelsOrderAndRestoresBook() {
        TradeStore failing = new TradeStore(jdbc) {
            @Override
            public void insert(Trade t) {
                throw new IllegalStateException("db down");
            }
        };
        TradingService service = new TradingService(catalog, accountStore, orderStore, failing, ledger, tx, Clock.systemUTC());
        service.recover();
        service.place(seller, "s-1", "lg", Side.SELL, OrderType.LIMIT, 18_500, 10);

        assertThatThrownBy(() -> service.place(buyer, "b-1", "lg", Side.BUY, OrderType.LIMIT, 18_500, 4))
                .isInstanceOf(TradingException.class).hasMessageContaining("취소");

        assertThat(orderStore.findByClientOrderId(buyer, "b-1").orElseThrow().status())
                .isEqualTo(StoredOrder.Status.CANCELED);
        assertThat(account(buyer).availablePoints()).isEqualTo(TradingService.START_POINTS);
        assertThat(account(buyer).lockedPoints()).isZero();
        assertThat(service.orderBook("lg", 5).asks().getFirst().quantity()).isEqualTo(10);
        assertThat(service.orderBook("lg", 5).bids()).isEmpty();
        assertThat(reconciliation.run().balanced()).isTrue();
    }

    /** 여러 스레드가 동시에 주문·취소해도 포인트와 주식 총량이 보존되고 호가가 교차하지 않아야 한다 */
    @Test
    void concurrentTradingConservesPointsAndShares() throws Exception {
        List<String> accounts = new ArrayList<>(List.of(buyer, seller));
        for (int i = 0; i < 10; i++) accounts.add(trading.openAccount("u" + i).id());
        List<Team> teams = catalog.all().subList(0, 3);
        for (String a : accounts) for (Team t : teams) trading.grantShares(a, t.id(), 200, t.basePrice());
        long pointsBefore = totalPoints();
        List<Long> sharesBefore = teams.stream().map(this::totalShares).toList();

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> jobs = new ArrayList<>();
        for (int w = 0; w < 8; w++) {
            long seed = w;
            jobs.add(pool.submit(() -> randomTrading(new Random(seed), accounts, teams, 300)));
        }
        for (Future<?> f : jobs) f.get(120, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(totalPoints()).isEqualTo(pointsBefore);
        for (int i = 0; i < teams.size(); i++) {
            Team t = teams.get(i);
            assertThat(totalShares(t)).isEqualTo(sharesBefore.get(i));
            var book = trading.orderBook(t.id(), 1);
            if (!book.asks().isEmpty() && !book.bids().isEmpty())
                assertThat(book.bids().getFirst().price()).isLessThan(book.asks().getFirst().price());
        }
        // 묶인 포인트는 대기 매수 주문의 예치 합과 정확히 같아야 한다
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(locked_points), 0) FROM accounts", Long.class))
                .isEqualTo(jdbc.queryForObject(
                        "SELECT COALESCE(SUM(reserve_per_share * remaining), 0) FROM orders WHERE status = 'OPEN' AND side = 'BUY'",
                        Long.class));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trades", Long.class)).isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE status = 'PENDING'", Long.class)).isZero();
        Reconciliation.Report report = reconciliation.run();
        assertThat(report.mismatches()).isEmpty();
        assertThat(report.unbalancedJournals()).isEmpty();
    }

    @Test
    void ledgerRecordsEveryMoveOfATrade() {
        place(seller, Side.SELL, OrderType.LIMIT, 18_400, 10);
        place(buyer, Side.BUY, OrderType.MARKET, 0, 4);

        // 시장가 매수는 상한가(23,950)로 묶였다가 18,400에 체결되고 차액을 돌려받는다
        assertThat(jdbc.queryForList(
                "SELECT kind FROM ledger_journals WHERE kind <> 'OPEN_ACCOUNT' AND kind <> 'GRANT' ORDER BY id", String.class))
                .containsExactly("LOCK", "LOCK", "TRADE");
        Long tradeJournal = jdbc.queryForObject("SELECT id FROM ledger_journals WHERE kind = 'TRADE'", Long.class);
        assertThat(jdbc.queryForList("SELECT owner_id, asset, bucket, amount FROM ledger_entries WHERE journal_id = ? ORDER BY id",
                tradeJournal)).extracting(m -> List.of(m.get("owner_id"), m.get("asset"), m.get("bucket"), m.get("amount")))
                .containsExactly(
                        List.of(buyer, "POINT", "LOCKED", -4L * 23_950),
                        List.of(buyer, "POINT", "AVAILABLE", 4L * (23_950 - 18_400)),
                        List.of(seller, "POINT", "AVAILABLE", 4L * 18_400),
                        List.of(seller, "lg", "LOCKED", -4L),
                        List.of(buyer, "lg", "AVAILABLE", 4L));
        assertThat(reconciliation.run().balanced()).isTrue();
    }

    @Test
    void reconciliationFindsBalanceChangedOutsideTheLedger() {
        place(buyer, Side.BUY, OrderType.LIMIT, 18_000, 10);
        jdbc.update("UPDATE accounts SET available_points = available_points + 5000 WHERE id = ?", buyer);

        Reconciliation.Report report = reconciliation.run();

        assertThat(report.balanced()).isFalse();
        assertThat(report.mismatches()).containsExactly(new Reconciliation.Mismatch(
                buyer, "POINT", Journal.Bucket.AVAILABLE, TradingService.START_POINTS - 180_000,
                TradingService.START_POINTS - 175_000));
    }

    @Test
    void reconciliationFindsUnbalancedJournal() {
        Long journal = jdbc.queryForObject("SELECT MIN(id) FROM ledger_journals", Long.class);
        jdbc.update("UPDATE ledger_entries SET amount = amount + 1 WHERE journal_id = ? AND owner_id = 'SYSTEM'", journal);

        assertThat(reconciliation.run().unbalancedJournals()).containsExactly(journal);
    }

    @Test
    void journalMustBalancePerAsset() {
        assertThatThrownBy(() -> new Journal(Journal.Kind.GRANT, null, null, List.of(
                new Journal.Entry(buyer, "lg", Journal.Bucket.AVAILABLE, 10))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void randomTrading(Random rnd, List<String> accounts, List<Team> teams, int steps) {
        for (int i = 0; i < steps; i++) {
            String a = accounts.get(rnd.nextInt(accounts.size()));
            Team t = teams.get(rnd.nextInt(teams.size()));
            try {
                int roll = rnd.nextInt(10);
                if (roll == 0) {
                    var mine = trading.openOrders(a);
                    if (!mine.isEmpty()) trading.cancel(a, mine.getFirst().id());
                } else {
                    Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
                    OrderType type = roll == 1 ? OrderType.MARKET : OrderType.LIMIT;
                    long price = t.basePrice() + (rnd.nextInt(11) - 5) * 10L;
                    trading.place(a, UUID.randomUUID().toString(), t.id(), side, type, price, 1 + rnd.nextInt(10));
                }
            } catch (TradingException | NotFoundException expected) {
                // 잔고 부족이나 이미 체결된 주문 취소는 정상적인 거절이다
            }
        }
    }

    private long totalPoints() {
        return jdbc.queryForObject("SELECT SUM(available_points + locked_points) FROM accounts", Long.class);
    }

    private long totalShares(Team t) {
        return jdbc.queryForObject("SELECT SUM(available + locked) FROM positions WHERE team_id = ?", Long.class, t.id());
    }
}
