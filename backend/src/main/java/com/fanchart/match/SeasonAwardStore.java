package com.fanchart.match;

import com.fanchart.team.League;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
class SeasonAwardStore {

    private static final RowMapper<SeasonAward> AWARD = (rs, i) -> new SeasonAward(rs.getLong("id"),
            League.valueOf(rs.getString("league")), rs.getString("season"), rs.getString("team_id"),
            SeasonAward.Award.valueOf(rs.getString("award")), rs.getLong("dps"),
            rs.getTimestamp("record_at").toInstant(), SeasonAward.Status.valueOf(rs.getString("status")),
            rs.getString("source_url"), rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(),
            rs.getString("decided_by"), instant(rs.getTimestamp("decided_at")),
            (Long) rs.getObject("shares", Long.class), (Integer) rs.getObject("holders", Integer.class),
            (Long) rs.getObject("total", Long.class));

    private final JdbcTemplate jdbc;

    SeasonAwardStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    long insert(League league, String season, String teamId, SeasonAward.Award award, long dps, Instant recordAt,
                String sourceUrl, String createdBy, Instant now) {
        String base = league.name() + ":" + season + ":";
        jdbc.update("""
                INSERT INTO season_awards (league, season, team_id, award, dps, record_at, status, source_url,
                    created_by, created_at, award_key, placement_key, title_key)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?)""",
                league.name(), season, teamId, award.name(), dps, Timestamp.from(recordAt), sourceUrl, createdBy,
                Timestamp.from(now), base + teamId + ":" + award.name(),
                award.placement() ? base + teamId : null, award.title() ? base + award.name() : null);
        Long id = jdbc.queryForObject("SELECT id FROM season_awards WHERE award_key = ?", Long.class,
                base + teamId + ":" + award.name());
        return id == null ? 0 : id;
    }

    Optional<SeasonAward> find(long id) {
        return jdbc.query("SELECT * FROM season_awards WHERE id = ?", AWARD, id).stream().findFirst();
    }

    Optional<SeasonAward> lock(long id) {
        return jdbc.query("SELECT * FROM season_awards WHERE id = ? FOR UPDATE", AWARD, id).stream().findFirst();
    }

    List<Long> due(Instant createdBefore) {
        return jdbc.queryForList("""
                SELECT id FROM season_awards WHERE status = 'PENDING' AND created_at <= ? ORDER BY id""",
                Long.class, Timestamp.from(createdBefore));
    }

    List<SeasonAward> latest(int limit) {
        return jdbc.query("SELECT * FROM season_awards ORDER BY id DESC LIMIT ?", AWARD, limit);
    }

    void paid(long id, String decidedBy, long shares, int holders, long total, Instant now) {
        jdbc.update("""
                UPDATE season_awards SET status = 'PAID', decided_by = ?, decided_at = ?, shares = ?, holders = ?,
                    total = ? WHERE id = ?""", decidedBy, Timestamp.from(now), shares, holders, total, id);
    }

    /** 취소하면 같은 성적을 다시 등록할 수 있게 고유 키를 비운다 */
    void voided(long id, String decidedBy, Instant now) {
        jdbc.update("""
                UPDATE season_awards SET status = 'VOID', decided_by = ?, decided_at = ?, award_key = NULL,
                    placement_key = NULL, title_key = NULL WHERE id = ?""", decidedBy, Timestamp.from(now), id);
    }
}
