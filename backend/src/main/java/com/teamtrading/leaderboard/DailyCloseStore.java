package com.teamtrading.leaderboard;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

@Repository
class DailyCloseStore {

    record Snapshot(long equity, long inflow) {
    }

    private final JdbcTemplate jdbc;

    DailyCloseStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<LocalDate> lastClosedDate() {
        Date d = jdbc.queryForObject("SELECT MAX(close_date) FROM daily_closes", Date.class);
        return Optional.ofNullable(d).map(Date::toLocalDate);
    }

    Optional<Instant> firstAccountCreatedAt() {
        Timestamp t = jdbc.queryForObject("SELECT MIN(created_at) FROM accounts", Timestamp.class);
        return Optional.ofNullable(t).map(Timestamp::toInstant);
    }

    boolean closed(LocalDate date) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM daily_closes WHERE close_date = ?", Integer.class,
                Date.valueOf(date));
        return n != null && n > 0;
    }

    /** 마감 직전 window 동안의 거래량가중평균가. 거래가 없으면 마감 전 마지막 체결가 */
    Optional<Long> closePrice(String teamId, Instant windowStart, Instant closeAt) {
        List<Long> vwap = jdbc.query("""
                SELECT SUM(price * quantity), SUM(quantity) FROM trades
                WHERE team_id = ? AND executed_at >= ? AND executed_at < ?""",
                (rs, i) -> rs.getLong(2) == 0 ? null : Math.round((double) rs.getLong(1) / rs.getLong(2)),
                teamId, Timestamp.from(windowStart), Timestamp.from(closeAt));
        if (!vwap.isEmpty() && vwap.getFirst() != null) return Optional.of(vwap.getFirst());
        return jdbc.queryForList("""
                SELECT price FROM trades WHERE team_id = ? AND executed_at < ?
                ORDER BY executed_at DESC, id DESC LIMIT 1""", Long.class, teamId, Timestamp.from(closeAt))
                .stream().findFirst();
    }

    /** 계정 → 자산(POINT 또는 구단 id) → 마감 시각 직전 잔고(가능 + 묶임). 원장 전표를 더해 되짚는다 */
    Map<String, Map<String, Long>> balancesBefore(Instant at) {
        Map<String, Map<String, Long>> out = new HashMap<>();
        jdbc.query("""
                SELECT e.owner_id, e.asset, SUM(e.amount) FROM ledger_entries e
                JOIN ledger_journals j ON j.id = e.journal_id
                WHERE e.owner_id <> 'SYSTEM' AND e.bucket IN ('AVAILABLE', 'LOCKED') AND j.created_at < ?
                GROUP BY e.owner_id, e.asset""",
                rs -> {
                    out.computeIfAbsent(rs.getString(1), k -> new HashMap<>()).put(rs.getString(2), rs.getLong(3));
                }, Timestamp.from(at));
        return out;
    }

    /** 계정별 외부 유입 합 [from, to) */
    Map<String, Long> inflows(List<String> sources, Instant from, Instant to) {
        Map<String, Long> out = new HashMap<>();
        String in = String.join(",", sources.stream().map(s -> "?").toList());
        Object[] args = new Object[sources.size() + 2];
        for (int i = 0; i < sources.size(); i++) args[i] = sources.get(i);
        args[sources.size()] = Timestamp.from(from);
        args[sources.size() + 1] = Timestamp.from(to);
        jdbc.query("SELECT account_id, SUM(amount) FROM point_grants WHERE source IN (" + in + ")"
                        + " AND granted_at >= ? AND granted_at < ? GROUP BY account_id",
                rs -> {
                    out.put(rs.getString(1), rs.getLong(2));
                }, args);
        return out;
    }

    /** 계정 → 구단 → 지급받은 주식 수 [from, to). 시즌 초 주식 지급도 외부 유입이다 */
    Map<String, Map<String, Long>> grantedShares(Instant from, Instant to) {
        Map<String, Map<String, Long>> out = new HashMap<>();
        jdbc.query("""
                SELECT e.owner_id, e.asset, SUM(e.amount) FROM ledger_entries e
                JOIN ledger_journals j ON j.id = e.journal_id
                WHERE j.kind = 'GRANT' AND e.owner_id <> 'SYSTEM' AND j.created_at >= ? AND j.created_at < ?
                GROUP BY e.owner_id, e.asset""",
                rs -> {
                    out.computeIfAbsent(rs.getString(1), k -> new HashMap<>()).put(rs.getString(2), rs.getLong(3));
                }, Timestamp.from(from), Timestamp.from(to));
        return out;
    }

    void insertClose(String teamId, LocalDate date, long price) {
        jdbc.update("INSERT INTO daily_closes (team_id, close_date, price) VALUES (?, ?, ?)", teamId,
                Date.valueOf(date), price);
    }

    void insertSnapshots(LocalDate date, Map<String, Snapshot> snapshots) {
        jdbc.batchUpdate("INSERT INTO equity_snapshots (account_id, close_date, equity, inflow) VALUES (?, ?, ?, ?)",
                List.copyOf(snapshots.entrySet()), 500, (ps, e) -> {
                    ps.setString(1, e.getKey());
                    ps.setDate(2, Date.valueOf(date));
                    ps.setLong(3, e.getValue().equity());
                    ps.setLong(4, e.getValue().inflow());
                });
    }

    /** 계정 → 날짜 → 일말 평가금·유입 [from, to] */
    Map<String, TreeMap<LocalDate, Snapshot>> snapshots(LocalDate from, LocalDate to) {
        Map<String, TreeMap<LocalDate, Snapshot>> out = new HashMap<>();
        jdbc.query("SELECT account_id, close_date, equity, inflow FROM equity_snapshots WHERE close_date BETWEEN ? AND ?",
                rs -> {
                    out.computeIfAbsent(rs.getString(1), k -> new TreeMap<>())
                            .put(rs.getDate(2).toLocalDate(), new Snapshot(rs.getLong(3), rs.getLong(4)));
                }, Date.valueOf(from), Date.valueOf(to));
        return out;
    }

    Optional<LocalDate> firstSnapshotDate() {
        Date d = jdbc.queryForObject("SELECT MIN(close_date) FROM equity_snapshots", Date.class);
        return Optional.ofNullable(d).map(Date::toLocalDate);
    }

    /** 계정별 체결 횟수 [from, to) */
    Map<String, Integer> tradeCounts(Instant from, Instant to) {
        Map<String, Integer> out = new HashMap<>();
        jdbc.query("""
                SELECT account_id, COUNT(*) FROM (
                    SELECT buyer_id AS account_id FROM trades WHERE executed_at >= ? AND executed_at < ?
                    UNION ALL
                    SELECT seller_id FROM trades WHERE executed_at >= ? AND executed_at < ?
                ) t GROUP BY account_id""",
                rs -> {
                    out.put(rs.getString(1), rs.getInt(2));
                }, Timestamp.from(from), Timestamp.from(to), Timestamp.from(from), Timestamp.from(to));
        return out;
    }

    Map<String, Instant> accountCreatedAt() {
        Map<String, Instant> out = new HashMap<>();
        jdbc.query("SELECT id, created_at FROM accounts", rs -> {
            out.put(rs.getString(1), rs.getTimestamp(2).toInstant());
        });
        return out;
    }
}
