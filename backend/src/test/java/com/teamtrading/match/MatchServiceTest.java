package com.teamtrading.match;

import com.teamtrading.TestClock;
import com.teamtrading.account.AccountStore;
import com.teamtrading.engine.OrderType;
import com.teamtrading.engine.Side;
import com.teamtrading.ledger.Reconciliation;
import com.teamtrading.match.Match.DividendStatus;
import com.teamtrading.trading.TradingException;
import com.teamtrading.trading.TradingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
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
class MatchServiceTest {

    static final long WIN = DividendPolicy.winDps(com.teamtrading.team.League.KBO);

    @Autowired MatchService matches;
    @Autowired TradingService trading;
    @Autowired AccountStore accounts;
    @Autowired Reconciliation reconciliation;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestClock clock;

    private String early;   // 시작 전부터 보유
    private String late;    // 시작 후에 매수
    private Instant kickoff;

    @BeforeEach
    void setUp() {
        early = trading.openAccount("early").id();
        late = trading.openAccount("late").id();
        trading.grantShares(early, "lg", 10, 18_450);
        trading.grantShares(early, "kt", 5, 12_900);
        kickoff = clock.instant().plus(Duration.ofHours(1));
    }

    private Match scheduledLgKt() {
        return matches.schedule("lg", "kt", kickoff, "test", "g-1");
    }

    private long points(String id) {
        return accounts.find(id).orElseThrow().availablePoints();
    }

    @Test
    void paysHoldersAtKickoffIncludingSharesLockedInSellOrders() {
        Match m = scheduledLgKt();
        trading.place(early, "s-1", "lg", Side.SELL, OrderType.LIMIT, 18_500, 4);   // 시작 전 매도 주문(묶임)
        clock.set(kickoff.plusSeconds(60));
        trading.place(late, "b-1", "lg", Side.BUY, OrderType.LIMIT, 18_500, 3);     // 시작 후 체결
        long earlyBefore = points(early);
        long lateBefore = points(late);

        clock.advance(Duration.ofHours(3));
        matches.recordResult(m.id(), 5, 2);
        matches.settle(m.id());

        assertThat(points(early) - earlyBefore).isEqualTo(10 * WIN);
        assertThat(points(late)).isEqualTo(lateBefore);
        assertThat(matches.dividends(m.id())).singleElement()
                .satisfies(d -> {
                    assertThat(d.teamId()).isEqualTo("lg");
                    assertThat(d.shares()).isEqualTo(10);
                    assertThat(d.holders()).isEqualTo(1);
                });
        Reconciliation.Report report = reconciliation.run();
        assertThat(report.balanced()).isTrue();
        assertThat(report.issuance().bySource()).containsEntry("DIVIDEND", 10 * WIN);
    }

    @Test
    void drawPaysHalfToBothTeams() {
        Match m = scheduledLgKt();
        clock.set(kickoff.plus(Duration.ofHours(3)));
        long before = points(early);

        matches.recordResult(m.id(), 3, 3);
        matches.settle(m.id());

        assertThat(points(early) - before).isEqualTo((10 + 5) * (WIN / 2));
        assertThat(matches.dividends(m.id())).extracting(DividendRow::kind).containsOnly("DRAW");
    }

    @Test
    void waitsForConfirmationDelayAndPaysOnce() {
        Match m = scheduledLgKt();
        clock.set(kickoff.plus(Duration.ofHours(3)));
        matches.recordResult(m.id(), 1, 0);
        long before = points(early);

        assertThat(matches.payDue()).isZero();
        clock.advance(Duration.ofHours(6));
        assertThat(matches.payDue()).isEqualTo(1);
        assertThat(matches.payDue()).isZero();
        assertThat(matches.settle(m.id())).isFalse();

        assertThat(points(early) - before).isEqualTo(10 * WIN);
        assertThat(matches.match(m.id()).dividendStatus()).isEqualTo(DividendStatus.PAID);
    }

    @Test
    void correctionBeforePaymentRestartsTheWaitAndPaysTheCorrectedResult() {
        Match m = scheduledLgKt();
        clock.set(kickoff.plus(Duration.ofHours(3)));
        matches.recordResult(m.id(), 2, 1);
        clock.advance(Duration.ofHours(5));
        matches.recordResult(m.id(), 2, 4);   // 정정: kt 승
        clock.advance(Duration.ofHours(2));
        assertThat(matches.payDue()).isZero();   // 정정 후 6시간이 안 됐다

        clock.advance(Duration.ofHours(5));
        long before = points(early);
        assertThat(matches.payDue()).isEqualTo(1);
        assertThat(points(early) - before).isEqualTo(5 * WIN);
    }

    @Test
    void correctionAfterPaymentIsFlaggedForReviewWithoutPayingAgain() {
        Match m = scheduledLgKt();
        clock.set(kickoff.plus(Duration.ofHours(3)));
        matches.recordResult(m.id(), 2, 1);
        matches.settle(m.id());
        long after = points(early);

        matches.recordResult(m.id(), 1, 2);

        assertThat(matches.match(m.id()).dividendStatus()).isEqualTo(DividendStatus.REVIEW);
        assertThat(matches.settle(m.id())).isFalse();
        assertThat(points(early)).isEqualTo(after);
    }

    @Test
    void canceledMatchPaysNothing() {
        Match m = scheduledLgKt();
        long before = points(early);
        matches.cancel(m.id());
        clock.advance(Duration.ofHours(7));
        matches.payDue();

        assertThat(matches.match(m.id()).dividendStatus()).isEqualTo(DividendStatus.VOID);
        assertThat(points(early)).isEqualTo(before);
    }

    @Test
    void rejectsResultBeforeKickoffAndUnknownOrCrossLeagueTeams() {
        Match m = scheduledLgKt();
        assertThatThrownBy(() -> matches.recordResult(m.id(), 1, 0)).isInstanceOf(TradingException.class);
        assertThatThrownBy(() -> matches.schedule("lg", "t1", kickoff, "test", "x")).isInstanceOf(TradingException.class);
        assertThatThrownBy(() -> matches.schedule("lg", "lg", kickoff, "test", "y")).isInstanceOf(TradingException.class);
    }

    @Test
    void postponementMovesTheRecordTime() {
        scheduledLgKt();
        Instant newKickoff = kickoff.plus(Duration.ofDays(1));
        Match m = matches.schedule("lg", "kt", newKickoff, "test", "g-1");   // 같은 경기, 하루 연기
        assertThat(m.scheduledAt()).isEqualTo(newKickoff);

        clock.set(kickoff.plusSeconds(60));   // 원래 시작 시각 뒤, 새 시작 시각 전에 매수
        trading.place(early, "s-1", "lg", Side.SELL, OrderType.LIMIT, 18_500, 4);
        trading.place(late, "b-1", "lg", Side.BUY, OrderType.LIMIT, 18_500, 4);
        clock.set(newKickoff.plus(Duration.ofHours(3)));
        long lateBefore = points(late);
        matches.recordResult(m.id(), 3, 0);
        matches.settle(m.id());

        assertThat(points(late) - lateBefore).isEqualTo(4 * WIN);
    }

    @Test
    void dividendsCountAsInvestmentReturn() {
        Match m = scheduledLgKt();
        long initial = accounts.find(early).orElseThrow().initialValue();
        clock.set(kickoff.plus(Duration.ofHours(3)));
        matches.recordResult(m.id(), 1, 0);
        matches.settle(m.id());

        assertThat(accounts.find(early).orElseThrow().initialValue()).isEqualTo(initial);
    }

    @Test
    void concurrentSettlementPaysOnce() throws Exception {
        Match m = scheduledLgKt();
        clock.set(kickoff.plus(Duration.ofHours(3)));
        matches.recordResult(m.id(), 1, 0);
        long before = points(early);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) results.add(pool.submit(() -> matches.settle(m.id())));
        int settled = 0;
        for (Future<Boolean> f : results) if (f.get()) settled++;
        pool.shutdown();

        assertThat(settled).isEqualTo(1);
        assertThat(points(early) - before).isEqualTo(10 * WIN);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_grants WHERE source = 'DIVIDEND'", Integer.class))
                .isEqualTo(1);
    }
}
