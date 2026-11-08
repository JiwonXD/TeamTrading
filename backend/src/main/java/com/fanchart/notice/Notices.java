package com.fanchart.notice;

import com.fanchart.team.TeamCatalog;
import com.fanchart.trading.NotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 공시. 운영자가 쓰고 고치고 지우며 누구나 읽는다. teamId가 null이면 모든 구단 화면에 보이는 전체 공시.
 * 지운 공시는 화면에서만 빠지고 행은 남는다(감사)
 */
@Service
public class Notices {

    /** @param teamId 전체 공시면 null */
    public record Notice(long id, String teamId, String title, String body, Instant createdAt, Instant updatedAt) {
    }

    private static final RowMapper<Notice> NOTICE = (rs, i) -> {
        Timestamp updated = rs.getTimestamp("updated_at");
        return new Notice(rs.getLong("id"), rs.getString("team_id"), rs.getString("title"), rs.getString("body"),
                rs.getTimestamp("created_at").toInstant(), updated == null ? null : updated.toInstant());
    };

    private final JdbcTemplate jdbc;
    private final TeamCatalog teams;
    private final Clock clock;

    Notices(JdbcTemplate jdbc, TeamCatalog teams, Clock clock) {
        this.jdbc = jdbc;
        this.teams = teams;
        this.clock = clock;
    }

    /** 구단 공시와 전체 공시를 최신부터 */
    public List<Notice> forTeam(String teamId, int limit) {
        requireTeam(teamId);
        return jdbc.query("""
                SELECT * FROM notices WHERE deleted_at IS NULL AND (team_id = ? OR team_id IS NULL)
                ORDER BY id DESC LIMIT ?""", NOTICE, teamId, limit);
    }

    public Notice write(String adminId, String teamId, String title, String body) {
        if (teamId != null) requireTeam(teamId);
        var key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO notices (team_id, title, body, created_by, created_at) VALUES (?, ?, ?, ?, ?)""",
                    new String[]{"id"});
            ps.setString(1, teamId);
            ps.setString(2, title.strip());
            ps.setString(3, body.strip());
            ps.setString(4, adminId);
            ps.setTimestamp(5, Timestamp.from(clock.instant()));
            return ps;
        }, key);
        return find(Objects.requireNonNull(key.getKey()).longValue());
    }

    public Notice edit(String adminId, long id, String title, String body) {
        int n = jdbc.update("""
                UPDATE notices SET title = ?, body = ?, updated_by = ?, updated_at = ? WHERE id = ? AND deleted_at IS NULL""",
                title.strip(), body.strip(), adminId, Timestamp.from(clock.instant()), id);
        if (n == 0) throw new NotFoundException("없는 공시예요");
        return find(id);
    }

    /** 이미 지운 공시를 다시 지워도 그대로 */
    public void delete(String adminId, long id) {
        jdbc.update("UPDATE notices SET deleted_by = ?, deleted_at = ? WHERE id = ? AND deleted_at IS NULL",
                adminId, Timestamp.from(clock.instant()), id);
    }

    private Notice find(long id) {
        return jdbc.query("SELECT * FROM notices WHERE id = ?", NOTICE, id).getFirst();
    }

    private void requireTeam(String teamId) {
        if (teams.find(teamId).isEmpty()) throw new NotFoundException("없는 구단이에요");
    }
}
