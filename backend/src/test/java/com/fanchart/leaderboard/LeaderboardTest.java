package com.fanchart.leaderboard;

import com.fanchart.TestClock;
import com.fanchart.engine.OrderType;
import com.fanchart.engine.Side;
import com.fanchart.leaderboard.DailyCloseStore.Snapshot;
import com.fanchart.leaderboard.Leaderboard.Board;
import com.fanchart.leaderboard.Leaderboard.Entry;
import com.fanchart.leaderboard.Leaderboard.Period;
import com.fanchart.leaderboard.Leaderboard.Sort;
import com.fanchart.points.PointIssuer;
import com.fanchart.points.PointSource;
import com.fanchart.trading.TradingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 시계는 2026-11-02 23:30 KST에서 시작한다 */
@SpringBootTest
@Import(TestClock.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class LeaderboardTest {

    static final LocalDate NOV2 = LocalDate.of(2026, 11, 2);

    @Autowired Leaderboard leaderboard;
    @Autowired DailyCloses closes;
    @Autowired TradingService trading;
    @Autowired PointIssuer points;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestClock clock;

    private final AtomicInteger orderNo = new AtomicInteger();

    private static Instant kst(int month, int day, int hour, int minute) {
        return LocalDateTime.of(2026, month, day, hour, minute).atZone(DailyCloses.KST).toInstant();
    }

    private void trade(String seller, String buyer, String team, long price, long qty) {
        trading.place(seller, "o" + orderNo.incrementAndGet(), team, Side.SELL, OrderType.LIMIT, price, qty);
        trading.place(buyer, "o" + orderNo.incrementAndGet(), team, Side.BUY, OrderType.LIMIT, price, qty);
    }

    private Snapshot snapshot(String account, LocalDate date) {
        return jdbc.queryForObject("SELECT equity, inflow FROM equity_snapshots WHERE account_id = ? AND close_date = ?",
                (rs, i) -> new Snapshot(rs.getLong(1), rs.getLong(2)), account, java.sql.Date.valueOf(date));
    }

    private long closePrice(String team, LocalDate date) {
        return jdbc.queryForObject("SELECT price FROM daily_closes WHERE team_id = ? AND close_date = ?", Long.class,
                team, java.sql.Date.valueOf(date));
    }

    @Test
    void closesAtLastTenMinuteVwapWithBalancesRebuiltFromTheLedgerAtMidnight() {
        String seller = trading.openAccount("seller").id();
        String buyer = trading.openAccount("buyer").id();
        trading.grantShares(seller, "lg", 100, 18_450);
        trade(seller, buyer, "lg", 18_500, 10);                 // 23:30, 종가 구간 밖
        clock.set(kst(11, 2, 23, 55));
        trade(seller, buyer, "lg", 18_600, 2);                  // 23:55, 종가 구간 안
        clock.set(kst(11, 3, 1, 0));
        trade(seller, buyer, "lg", 19_000, 5);                  // 다음 날 체결은 전날 마감에 들어가지 않는다

        assertThat(closes.close(NOV2)).isTrue();

        assertThat(closePrice("lg", NOV2)).isEqualTo(18_600);
        assertThat(closePrice("kt", NOV2)).isEqualTo(trading.quote("kt").team().basePrice());
        // 매수자: 포인트 1,000,000 - 185,000 - 37,200 + 12주 × 18,600
        assertThat(snapshot(buyer, NOV2)).isEqualTo(new Snapshot(1_001_000, 1_000_000));
        // 매도자: 포인트 1,222,200 + 88주 × 18,600. 지급받은 100주는 그날 종가로 유입에 넣는다
        assertThat(snapshot(seller, NOV2)).isEqualTo(new Snapshot(2_859_000, 1_000_000 + 100 * 18_600));
    }

    @Test
    void closeIsWrittenOnceAndCatchUpFillsDaysTheServerMissed() {
        String a = trading.openAccount("a").id();
        clock.set(kst(11, 5, 0, 30));

        assertThat(closes.catchUp()).isEqualTo(3);              // 11/2, 11/3, 11/4
        assertThat(closes.catchUp()).isZero();
        assertThat(closes.close(NOV2)).isFalse();
        assertThat(snapshot(a, NOV2.plusDays(2))).isEqualTo(new Snapshot(1_000_000, 0));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM equity_snapshots WHERE account_id = ?", Integer.class, a))
                .isEqualTo(3);
    }

    @Test
    void timeWeightedReturnIgnoresTheSizeOfInflows() {
        TreeMap<LocalDate, Snapshot> days = new TreeMap<>();
        days.put(NOV2, new Snapshot(1_000, 0));
        days.put(NOV2.plusDays(1), new Snapshot(1_100, 0));     // +10%
        days.put(NOV2.plusDays(2), new Snapshot(1_650, 500));   // 1,600에서 시작해 +3.125%

        double[] perf = Leaderboard.performance(days, NOV2.plusDays(1));

        assertThat(perf[0]).isCloseTo(1.1 * 1.03125 - 1, within(1e-12));
        assertThat(perf[1]).isEqualTo(150);
    }

    @Test
    void activityRewardsDoNotCountAsReturnButDividendsDo() {
        String rewarded = trading.openAccount("rewarded").id();
        String holder = trading.openAccount("holder").id();
        clock.set(kst(11, 3, 12, 0));
        points.grant(rewarded, PointSource.AD_VIEW, "ad-1");
        points.grantDividend(holder, "m1:lg:" + holder, 1_000);
        clock.set(kst(11, 4, 0, 30));

        Board a = leaderboard.board(Period.WEEK, Sort.RETURN, rewarded, 50);
        Board b = leaderboard.board(Period.WEEK, Sort.RETURN, holder, 50);

        assertThat(a.mine().returnRate()).isZero();
        assertThat(a.mine().pnl()).isZero();
        assertThat(b.mine().returnRate()).isCloseTo(0.001, within(1e-12));
        assertThat(b.mine().pnl()).isEqualTo(1_000);
    }

    @Test
    void todayComparesCurrentEquityWithYesterdaysClose() {
        String seller = trading.openAccount("seller").id();
        String buyer = trading.openAccount("buyer").id();
        trading.grantShares(seller, "lg", 50, 18_450);
        clock.set(kst(11, 3, 9, 0));
        trade(seller, buyer, "lg", 18_450, 10);
        trade(seller, buyer, "lg", 19_000, 1);                  // 현재가 19,000

        Board board = leaderboard.board(Period.TODAY, Sort.PNL, buyer, 50);

        // 어제 마감 1,000,000 → 지금 포인트 1,000,000 - 184,500 - 19,000 + 11주 × 19,000
        assertThat(board.mine().pnl()).isEqualTo(5_500);
        assertThat(board.mine().trades()).isEqualTo(2);
    }

    @Test
    void ranksOnlySeasonedTradersWithoutSurveillanceAlerts() {
        String fresh = trading.openAccount("fresh").id();
        String honest = trading.openAccount("honest").id();
        String counterparty = trading.openAccount("counterparty").id();
        String washA = trading.openAccount("washA").id();
        String washB = trading.openAccount("washB").id();
        trading.grantShares(counterparty, "lg", 50, 18_450);
        trading.grantShares(washA, "kt", 50, 12_900);

        Board early = leaderboard.board(Period.TODAY, Sort.RETURN, fresh, 50);
        assertThat(early.mine().rank()).isZero();
        assertThat(early.mine().reason()).contains("7일");

        clock.set(kst(11, 10, 9, 0));
        for (int i = 0; i < 5; i++) {
            trade(counterparty, honest, "lg", 18_450, 1);       // 11분 간격이라 같은 상대 반복 체결이 아니다
            clock.advance(Duration.ofMinutes(11));
        }
        for (int i = 0; i < 5; i++) trade(washA, washB, "kt", 12_900, 1); // 10분 안에 같은 상대와 5회

        Board board = leaderboard.board(Period.TODAY, Sort.RETURN, fresh, 50);

        assertThat(board.entries()).extracting(Entry::nickname).containsExactlyInAnyOrder("honest", "counterparty");
        assertThat(board.entries()).extracting(Entry::rank).containsExactly(1, 2);
        assertThat(board.mine().reason()).contains("5번");
        assertThat(leaderboard.board(Period.TODAY, Sort.RETURN, washA, 50).mine().reason()).contains("이상거래");
        assertThat(leaderboard.board(Period.TODAY, Sort.RETURN, honest, 50).mine().rank()).isPositive();
    }
}
