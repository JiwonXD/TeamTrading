package com.teamtrading.fds;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 이상거래 탐지 기록. 체결 정산 트랜잭션 안에서 쓴다 */
@Repository
public class AlertStore {

    private static final RowMapper<Alert> ALERT = (rs, i) -> new Alert(AlertKind.valueOf(rs.getString("kind")),
            rs.getString("team_id"), rs.getLong("trade_id"), rs.getString("account_a"), rs.getString("account_b"),
            rs.getString("message"), rs.getTimestamp("detected_at").toInstant());

    private final JdbcTemplate jdbc;

    public AlertStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(List<Alert> alerts) {
        if (alerts.isEmpty()) return;
        jdbc.batchUpdate("""
                INSERT INTO fds_alerts (kind, team_id, trade_id, account_a, account_b, message, detected_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", alerts, alerts.size(), (ps, a) -> {
            ps.setString(1, a.kind().name());
            ps.setString(2, a.teamId());
            ps.setLong(3, a.tradeId());
            ps.setString(4, a.accountA());
            ps.setString(5, a.accountB());
            ps.setString(6, a.message());
            ps.setTimestamp(7, Timestamp.from(a.detectedAt()));
        });
    }

    /** since 이후 탐지 기록에 나온 계정(양쪽 모두) */
    public Set<String> flaggedSince(Instant since) {
        Set<String> out = new HashSet<>();
        jdbc.query("SELECT account_a, account_b FROM fds_alerts WHERE detected_at >= ?", rs -> {
            out.add(rs.getString(1));
            out.add(rs.getString(2));
        }, Timestamp.from(since));
        return out;
    }

    /** 최근 기록. 최신이 앞 */
    public List<Alert> recent(int limit) {
        return jdbc.query("SELECT * FROM fds_alerts ORDER BY id DESC LIMIT ?", ALERT, limit);
    }
}
