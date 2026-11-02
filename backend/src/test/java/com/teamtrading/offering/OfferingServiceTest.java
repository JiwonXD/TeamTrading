package com.teamtrading.offering;

import com.teamtrading.TestClock;
import com.teamtrading.account.AccountStore;
import com.teamtrading.engine.OrderType;
import com.teamtrading.engine.Side;
import com.teamtrading.ledger.Reconciliation;
import com.teamtrading.offering.Offering.Status;
import com.teamtrading.trading.IdempotencyConflictException;
import com.teamtrading.trading.TradingException;
import com.teamtrading.trading.TradingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestClock.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class OfferingServiceTest {

    static final long PRICE = 18_450;   // lg 기준가

    @Autowired OfferingService offerings;
    @Autowired TradingService trading;
    @Autowired AccountStore accounts;
    @Autowired Reconciliation reconciliation;
    @Autowired TestClock clock;

    private String a, b, c;
    private Instant closeAt, listingAt;

    @BeforeEach
    void setUp() {
        a = trading.openAccount("a").id();
        b = trading.openAccount("b").id();
        c = trading.openAccount("c").id();
        closeAt = clock.instant().plus(Duration.ofHours(1));
        listingAt = clock.instant().plus(Duration.ofHours(2));
    }

    private Offering lgIpo() {
        return offerings.create("lg", null, 100, 50, clock.instant(), closeAt, listingAt);
    }

    private void buy(String who, long qty) {
        trading.place(who, "buy-" + who + qty, "lg", Side.BUY, OrderType.LIMIT, PRICE, qty);
    }

    private long available(String id) {
        return accounts.find(id).orElseThrow().availablePoints();
    }

    @Test
    void oversubscribedIpoAllocatesBurnsPaymentAndRefundsTheRest() {
        Offering o = lgIpo();
        assertThat(o.price()).isEqualTo(PRICE);
        assertThat(o.round()).isEqualTo(1);
        assertThat(o.firstListing()).isTrue();
        offerings.subscribe(o.id(), a, 50);
        offerings.subscribe(o.id(), b, 50);
        offerings.subscribe(o.id(), c, 10);
        assertThat(accounts.find(a).orElseThrow().lockedPoints()).isEqualTo(50 * PRICE);
        assertThat(offerings.view(o.id()).requested()).isEqualTo(110);

        clock.set(closeAt);
        assertThat(offerings.allocate(o.id())).isTrue();
        assertThat(offerings.allocate(o.id())).isFalse();

        assertThat(trading.holdings(a)).singleElement().satisfies(h -> assertThat(h.total()).isEqualTo(45));
        assertThat(trading.holdings(c)).singleElement().satisfies(h -> assertThat(h.total()).isEqualTo(10));
        assertThat(available(a)).isEqualTo(1_000_000 - 45 * PRICE);
        assertThat(accounts.find(a).orElseThrow().lockedPoints()).isZero();
        assertThat(offerings.view(o.id()).offering().allocatedShares()).isEqualTo(100);
        assertThat(offerings.mine(a, 10)).singleElement().satisfies(s -> assertThat(s.allocated()).isEqualTo(45));

        var report = reconciliation.run();
        assertThat(report.balanced()).isTrue();
        assertThat(report.issuance().ledgerBurned()).isEqualTo(100 * PRICE);
    }

    @Test
    void firstListingBlocksTradingUntilAllocatedAndListed() {
        Offering o = lgIpo();
        assertThatThrownBy(() -> buy(a, 1)).isInstanceOf(TradingException.class).hasMessageContaining("청약 중");
        offerings.subscribe(o.id(), a, 10);
        offerings.subscribe(o.id(), b, 10);

        clock.set(closeAt.plusSeconds(30));
        assertThatThrownBy(() -> buy(b, 1)).hasMessageContaining("청약 중");   // 마감했지만 아직 배정 전
        offerings.allocateDue();
        assertThatThrownBy(() -> buy(b, 1)).hasMessageContaining("상장 전");

        // 재기동해도 막힌다
        trading.holdUntil("lg", null);
        offerings.restoreListingHolds();
        assertThatThrownBy(() -> buy(b, 1)).hasMessageContaining("상장 전");

        clock.set(listingAt);
        trading.place(a, "sell-a", "lg", Side.SELL, OrderType.LIMIT, PRICE, 3);
        buy(b, 3);
        assertThat(trading.holdings(b)).singleElement().satisfies(h -> assertThat(h.total()).isEqualTo(13));
        assertThat(reconciliation.run().balanced()).isTrue();
    }

    @Test
    void subscriptionIsOncePerAccountWithinTheWindowAndWithinLimits() {
        Offering o = lgIpo();
        assertThatThrownBy(() -> offerings.subscribe(o.id(), a, 51)).hasMessageContaining("한도");
        var first = offerings.subscribe(o.id(), a, 20);
        assertThat(offerings.subscribe(o.id(), a, 20)).isEqualTo(first);
        assertThatThrownBy(() -> offerings.subscribe(o.id(), a, 30)).isInstanceOf(IdempotencyConflictException.class);

        offerings.cancelSubscription(o.id(), a);
        assertThat(available(a)).isEqualTo(1_000_000);
        offerings.subscribe(o.id(), a, 30);

        assertThatThrownBy(() -> offerings.subscribe(o.id(), b, 60)).hasMessageContaining("한도");

        clock.set(closeAt);
        assertThatThrownBy(() -> offerings.subscribe(o.id(), b, 1)).hasMessageContaining("청약 기간");
        assertThatThrownBy(() -> offerings.cancelSubscription(o.id(), a)).hasMessageContaining("청약 기간");
        assertThat(reconciliation.run().balanced()).isTrue();
    }

    @Test
    void depositMustBeCoveredByAvailablePoints() {
        Offering o = offerings.create("lg", null, 1_000, 1_000, clock.instant(), closeAt, listingAt);
        assertThatThrownBy(() -> offerings.subscribe(o.id(), a, 60)).hasMessageContaining("부족");
        assertThat(available(a)).isEqualTo(1_000_000);
    }

    @Test
    void canceledOfferingRefundsEveryoneAndLiftsTheHold() {
        Offering o = lgIpo();
        offerings.subscribe(o.id(), a, 10);
        offerings.subscribe(o.id(), b, 20);

        assertThat(offerings.cancel(o.id()).status()).isEqualTo(Status.CANCELED);

        assertThat(available(a)).isEqualTo(1_000_000);
        assertThat(available(b)).isEqualTo(1_000_000);
        buy(a, 1);   // 막지 않는다(판매 호가가 없어 대기)
        assertThat(reconciliation.run().balanced()).isTrue();
    }

    @Test
    void followOnOfferingDoesNotStopTrading() {
        trading.grantShares(c, "lg", 10, PRICE);
        Offering followOn = lgIpo();
        assertThat(followOn.firstListing()).isFalse();
        trading.place(c, "sell-c", "lg", Side.SELL, OrderType.LIMIT, PRICE, 1);
        buy(a, 1);
        assertThat(trading.holdings(a)).hasSize(1);
        assertThatThrownBy(() -> offerings.create("lg", null, 100, 50, clock.instant(), closeAt, closeAt))
                .hasMessageContaining("이미 청약 중");
    }

    @Test
    void concurrentSubscriptionsAreAllCountedExactlyOnce() throws Exception {
        Offering o = offerings.create("lg", null, 500, 20, clock.instant(), closeAt, listingAt);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 40; i++) ids.add(trading.openAccount("s" + i).id());

        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            List<Future<?>> futures = new ArrayList<>();
            for (String id : ids) {
                // 같은 계정이 같은 청약을 두 번 보내도(재시도) 한 번만 묶인다
                futures.add(pool.submit(() -> offerings.subscribe(o.id(), id, 20)));
                futures.add(pool.submit(() -> offerings.subscribe(o.id(), id, 20)));
            }
            for (Future<?> f : futures) f.get();
        }
        assertThat(offerings.view(o.id()).subscribers()).isEqualTo(40);
        assertThat(offerings.view(o.id()).requested()).isEqualTo(800);
        for (String id : ids) assertThat(accounts.find(id).orElseThrow().lockedPoints()).isEqualTo(20 * PRICE);

        clock.set(closeAt);
        assertThat(offerings.allocateDue()).isEqualTo(1);
        long allocated = ids.stream().mapToLong(id -> trading.holdings(id).stream().mapToLong(h -> h.total()).sum()).sum();
        assertThat(allocated).isEqualTo(500);
        assertThat(reconciliation.run().balanced()).isTrue();
    }
}
