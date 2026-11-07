package com.fanchart.leaderboard;

import com.fanchart.account.Account;
import com.fanchart.fds.AlertStore;
import com.fanchart.leaderboard.DailyCloseStore.Snapshot;
import com.fanchart.trading.TradingService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 수익률 리더보드. 수익률은 시간가중수익률(TWR)이다.
 * 하루를 한 구간으로 보고 구간 수익률 r = (E_t - F_t - E_{t-1}) / (E_{t-1} + F_t)를 곱해 잇는다.
 * E는 일말 평가금, F는 그날 들어온 외부 유입(가입·광고·게시글 보상, 지급받은 주식)이고 유입은 그날 시작에 들어온 것으로 본다.
 * 그래서 보상을 많이 받아도 수익률이 오르지 않고, 배당은 수익으로 잡힌다.
 * 손익은 Σ(E_t - E_{t-1} - F_t). 오늘(TODAY)은 어제 마감 평가금 대비 지금 평가금(현재가 기준)이다.
 */
@Service
@EnableConfigurationProperties(LeaderboardProperties.class)
public class Leaderboard {

    public enum Period { TODAY, WEEK, MONTH, SEASON }

    public enum Sort { RETURN, PNL }

    public record Entry(int rank, String accountId, String nickname, double returnRate, long pnl, int trades) {
    }

    /** 내 성적. 순위 자격이 없으면 rank는 0이고 reason에 이유 */
    public record Mine(int rank, double returnRate, long pnl, int trades, String reason) {
    }

    public record Board(Period period, LocalDate from, LocalDate to, List<Entry> entries, Mine mine) {
    }

    private final DailyCloses closes;
    private final DailyCloseStore store;
    private final TradingService trading;
    private final AlertStore alerts;
    private final LeaderboardProperties props;
    private final Clock clock;

    Leaderboard(DailyCloses closes, DailyCloseStore store, TradingService trading, AlertStore alerts,
                LeaderboardProperties props, Clock clock) {
        this.closes = closes;
        this.store = store;
        this.trading = trading;
        this.alerts = alerts;
        this.props = props;
        this.clock = clock;
    }

    public Board board(Period period, Sort sort, String me, int limit) {
        closes.catchUp();
        LocalDate today = closes.today();
        LocalDate to = period == Period.TODAY ? today : today.minusDays(1);
        LocalDate from = switch (period) {
            case TODAY -> today;
            case WEEK -> today.minusDays(7);
            case MONTH -> today.minusDays(30);
            case SEASON -> store.firstSnapshotDate().orElse(today);
        };
        Instant windowStart = DailyCloses.startOf(from);
        Instant windowEnd = DailyCloses.startOf(to.plusDays(1));

        Map<String, TreeMap<LocalDate, Snapshot>> days = store.snapshots(from.minusDays(1), to);
        if (period == Period.TODAY) addLive(days, today, windowStart);

        Map<String, Integer> trades = store.tradeCounts(windowStart, windowEnd);
        Map<String, Instant> created = store.accountCreatedAt();
        Set<String> flagged = flagged(windowStart);
        Map<String, String> names = new HashMap<>();
        Set<String> withdrawn = new HashSet<>();
        for (Account a : trading.allAccounts()) {
            names.put(a.id(), a.nickname());
            if (a.withdrawn()) withdrawn.add(a.id());
        }

        record Row(String id, double rate, long pnl, int trades) {
        }
        List<Row> eligible = new ArrayList<>();
        Row mine = null;
        String reason = null;
        for (var e : days.entrySet()) {
            String id = e.getKey();
            // 탈퇴한 계정은 순위에서 뺀다. 남은 주식의 평가금은 계속 움직이지만 그 사람은 더 이상 없다
            if (withdrawn.contains(id)) continue;
            // 운영자 계정은 증자 물량을 들고 있는 발행자라 순위에 넣지 않는다
            if (trading.isIssuer(id)) continue;
            double[] perf = performance(e.getValue(), from);
            Row row = new Row(id, perf[0], (long) perf[1], trades.getOrDefault(id, 0));
            String why = ineligible(row.trades(), created.get(id), flagged.contains(id));
            if (why == null) eligible.add(row);
            if (id.equals(me)) {
                mine = row;
                reason = why;
            }
        }
        Comparator<Row> order = sort == Sort.PNL
                ? Comparator.comparingLong(Row::pnl).reversed().thenComparing(Row::id)
                : Comparator.comparingDouble(Row::rate).reversed().thenComparing(Row::id);
        eligible.sort(order);

        List<Entry> entries = new ArrayList<>();
        int myRank = 0;
        for (int i = 0; i < eligible.size(); i++) {
            Row r = eligible.get(i);
            if (r.id().equals(me)) myRank = i + 1;
            if (i < limit) entries.add(new Entry(i + 1, r.id(), names.getOrDefault(r.id(), "?"), r.rate(), r.pnl(), r.trades()));
        }
        Mine m = mine == null ? null : new Mine(myRank, mine.rate(), mine.pnl(), mine.trades(), reason);
        return new Board(period, from, to, entries, m);
    }

    /** [수익률, 손익]. from 전날 평가금을 시작값으로 from부터 마지막 날까지 잇는다 */
    static double[] performance(TreeMap<LocalDate, Snapshot> days, LocalDate from) {
        Snapshot start = days.get(from.minusDays(1));
        long prev = start == null ? 0 : start.equity();
        double growth = 1;
        long pnl = 0;
        for (var d : days.tailMap(from, true).entrySet()) {
            long equity = d.getValue().equity();
            long inflow = d.getValue().inflow();
            long base = prev + inflow;
            if (base > 0) growth *= (double) (equity - inflow - prev) / base + 1;
            pnl += equity - prev - inflow;
            prev = equity;
        }
        return new double[]{growth - 1, pnl};
    }

    private String ineligible(int trades, Instant createdAt, boolean flagged) {
        if (flagged) return "이상거래 탐지 기록이 있어 확인 중이에요";
        if (createdAt != null && createdAt.plus(props.minAge()).isAfter(clock.instant()))
            return "가입 후 " + props.minAge().toDays() + "일이 지나면 순위에 올라요";
        if (trades < props.minTrades()) return "이 기간에 " + props.minTrades() + "번 이상 체결하면 순위에 올라요";
        return null;
    }

    /** 기간 안에 탐지된 이상거래의 양쪽 계정 */
    private Set<String> flagged(Instant since) {
        return alerts.flaggedSince(since);
    }

    /** 오늘 구간: 지금 평가금(현재가)과 오늘 들어온 외부 유입 */
    private void addLive(Map<String, TreeMap<LocalDate, Snapshot>> days, LocalDate today, Instant dayStart) {
        Map<String, Long> prices = new HashMap<>();
        for (var q : trading.quotes()) prices.put(q.team().id(), q.lastPrice());
        Map<String, Long> inflows = closes.inflows(dayStart, DailyCloses.startOf(today.plusDays(1)), prices);
        for (Account a : trading.allAccounts()) {
            days.computeIfAbsent(a.id(), k -> new TreeMap<>())
                    .put(today, new Snapshot(trading.totalValue(a), inflows.getOrDefault(a.id(), 0L)));
        }
    }
}
