package com.fanchart.match;

import com.fanchart.team.Competition;
import com.fanchart.team.League;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Repository
class MatchStore {

    private static final RowMapper<Match> MATCH = (rs, i) -> new Match(rs.getLong("id"),
            League.valueOf(rs.getString("league")), Competition.valueOf(rs.getString("competition")),
            rs.getString("home_team_id"), rs.getString("home_name"), rs.getString("away_team_id"),
            rs.getString("away_name"),
            rs.getTimestamp("scheduled_at").toInstant(), Match.Status.valueOf(rs.getString("status")),
            (Integer) rs.getObject("home_score", Integer.class), (Integer) rs.getObject("away_score", Integer.class),
            rs.getTimestamp("final_at") == null ? null : rs.getTimestamp("final_at").toInstant(),
            rs.getString("source"), rs.getString("source_ref"),
            Match.DividendStatus.valueOf(rs.getString("dividend_status")));

    private final JdbcTemplate jdbc;

    MatchStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** home·away가 null이면 상장하지 않은 팀이고 이름(homeName, awayName)만 둔다 */
    long insert(League league, Competition competition, String home, String homeName, String away, String awayName,
                Instant scheduledAt, String source, String sourceRef, Instant now) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO matches (league, competition, home_team_id, home_name, away_team_id, away_name,
                                         scheduled_at, status, source, source_ref, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'SCHEDULED', ?, ?, ?)""", new String[]{"id"});
            ps.setString(1, league.name());
            ps.setString(2, competition.name());
            ps.setString(3, home);
            ps.setString(4, home == null ? homeName : null);
            ps.setString(5, away);
            ps.setString(6, away == null ? awayName : null);
            ps.setTimestamp(7, Timestamp.from(scheduledAt));
            ps.setString(8, source);
            ps.setString(9, sourceRef);
            ps.setTimestamp(10, Timestamp.from(now));
            return ps;
        }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    Optional<Match> find(long id) {
        return jdbc.query("SELECT * FROM matches WHERE id = ?", MATCH, id).stream().findFirst();
    }

    Optional<Match> findBySource(String source, String sourceRef) {
        return jdbc.query("SELECT * FROM matches WHERE source = ? AND source_ref = ?", MATCH, source, sourceRef)
                .stream().findFirst();
    }

    /** 트랜잭션이 끝날 때까지 경기 행을 잠그고 읽는다. 배당 지급이 두 곳에서 동시에 돌지 않게 한다 */
    Optional<Match> lock(long id) {
        return jdbc.query("SELECT * FROM matches WHERE id = ? FOR UPDATE", MATCH, id).stream().findFirst();
    }

    void reschedule(long id, Instant scheduledAt, Instant now) {
        jdbc.update("UPDATE matches SET scheduled_at = ?, updated_at = ? WHERE id = ? AND status = 'SCHEDULED'",
                Timestamp.from(scheduledAt), Timestamp.from(now), id);
    }

    void setResult(long id, Match.Status status, Integer home, Integer away, Instant now) {
        jdbc.update("""
                UPDATE matches SET status = ?, home_score = ?, away_score = ?, final_at = ?, updated_at = ?
                WHERE id = ?""", status.name(), home, away, Timestamp.from(now), Timestamp.from(now), id);
    }

    void setDividendStatus(long id, Match.DividendStatus status, Instant now) {
        jdbc.update("UPDATE matches SET dividend_status = ?, updated_at = ? WHERE id = ?",
                status.name(), Timestamp.from(now), id);
    }

    /** 결과가 들어온 지 confirmBefore 이전이고 아직 배당을 처리하지 않은 경기 */
    List<Long> due(Instant confirmBefore) {
        return jdbc.queryForList("""
                SELECT id FROM matches WHERE status IN ('FINAL', 'CANCELED') AND dividend_status = 'NONE'
                AND final_at <= ? ORDER BY id""", Long.class, Timestamp.from(confirmBefore));
    }

    List<Match> recent(String teamId, int limit) {
        return jdbc.query("""
                SELECT * FROM matches WHERE home_team_id = ? OR away_team_id = ?
                ORDER BY scheduled_at DESC LIMIT ?""", MATCH, teamId, teamId, limit);
    }

    /** 가장 최근 일정부터. 앞으로 열릴 경기도 포함한다 */
    List<Match> latest(int limit) {
        return jdbc.query("SELECT * FROM matches ORDER BY scheduled_at DESC, id DESC LIMIT ?", MATCH, limit);
    }

    /** 한 리그 구단이 나가는 모든 대회(정규 시즌·컵대회)의 [from, to) 사이 일정. 시작 시각 순 */
    List<Match> between(League league, Instant from, Instant to) {
        return jdbc.query("""
                SELECT * FROM matches WHERE scheduled_at >= ? AND scheduled_at < ?
                AND (home_team_id IN (SELECT id FROM teams WHERE league = ?)
                     OR away_team_id IN (SELECT id FROM teams WHERE league = ?))
                ORDER BY scheduled_at, id""", MATCH, Timestamp.from(from), Timestamp.from(to), league.name(),
                league.name());
    }

    /** 시작한 지 오래됐는데 결과가 없는 경기 */
    List<Match> startedWithoutResult(String source, Instant startedBefore) {
        return jdbc.query("SELECT * FROM matches WHERE source = ? AND status = 'SCHEDULED' AND scheduled_at <= ?",
                MATCH, source, Timestamp.from(startedBefore));
    }

    /**
     * 기준 시각에 구단 주식을 가진 계정과 수량. 잔고 테이블은 지금 값만 있으므로 원장 전표를 기준 시각까지 더해 되짚는다.
     * 매도 주문에 묶인 주식도 보유로 친다. 계정 id 순서로 돌려준다.
     */
    Map<String, Long> holdersAt(String teamId, Instant at) {
        Map<String, Long> holders = new LinkedHashMap<>();
        jdbc.query("""
                SELECT e.owner_id, SUM(e.amount) FROM ledger_entries e
                JOIN ledger_journals j ON j.id = e.journal_id
                WHERE e.asset = ? AND e.owner_id <> 'SYSTEM' AND e.bucket IN ('AVAILABLE', 'LOCKED') AND j.created_at <= ?
                GROUP BY e.owner_id HAVING SUM(e.amount) > 0 ORDER BY e.owner_id""",
                rs -> {
                    holders.put(rs.getString(1), rs.getLong(2));
                }, teamId, Timestamp.from(at));
        return holders;
    }

    void insertDividend(long matchId, String teamId, String kind, long dps, Instant recordAt, long shares,
                        int holders, long total, Instant now) {
        jdbc.update("""
                INSERT INTO dividends (match_id, team_id, kind, dps, record_at, shares, holders, total, paid_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", matchId, teamId, kind, dps, Timestamp.from(recordAt), shares,
                holders, total, Timestamp.from(now));
    }

    List<DividendRow> dividends(long matchId) {
        return jdbc.query("SELECT * FROM dividends WHERE match_id = ? ORDER BY team_id", (rs, i) -> new DividendRow(
                rs.getLong("match_id"), rs.getString("team_id"), rs.getString("kind"), rs.getLong("dps"),
                rs.getLong("shares"), rs.getInt("holders"), rs.getLong("total"),
                rs.getTimestamp("paid_at").toInstant()), matchId);
    }

    List<MyDividend> myDividends(String accountId, int limit) {
        return jdbc.query("""
                SELECT * FROM (
                    SELECT g.id AS grant_id, d.match_id, NULL AS award_id, m.competition, d.team_id, d.kind, d.dps,
                        g.amount, g.granted_at FROM point_grants g
                    JOIN dividends d ON g.source_ref = CONCAT(d.match_id, ':', d.team_id, ':', g.account_id)
                    JOIN matches m ON m.id = d.match_id
                    WHERE g.account_id = ? AND g.source = 'DIVIDEND'
                    UNION ALL
                    SELECT g.id, NULL, a.id, a.competition, a.team_id, a.award, a.dps, g.amount, g.granted_at
                    FROM point_grants g
                    JOIN season_awards a ON g.source_ref = CONCAT('award:', a.id, ':', g.account_id)
                    WHERE g.account_id = ? AND g.source = 'DIVIDEND'
                ) x ORDER BY grant_id DESC LIMIT ?""",
                (rs, i) -> new MyDividend((Long) rs.getObject("match_id", Long.class),
                        (Long) rs.getObject("award_id", Long.class), rs.getString("competition"),
                        rs.getString("team_id"), rs.getString("kind"),
                        rs.getLong("amount") / rs.getLong("dps"), rs.getLong("amount"),
                        rs.getTimestamp("granted_at").toInstant()),
                accountId, accountId, limit);
    }
}
