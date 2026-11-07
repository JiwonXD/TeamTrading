package com.teamtrading.points;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
class PointStore {

    private static final RowMapper<PointGrant> GRANT = (rs, i) -> new PointGrant(rs.getLong("id"),
            rs.getString("account_id"), PointSource.valueOf(rs.getString("source")), rs.getString("source_ref"),
            rs.getLong("amount"), rs.getLong("journal_id"), rs.getTimestamp("granted_at").toInstant());

    private final JdbcTemplate jdbc;

    PointStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<PointGrant> find(PointSource source, String sourceRef) {
        return jdbc.query("SELECT * FROM point_grants WHERE source = ? AND source_ref = ?", GRANT,
                source.name(), sourceRef).stream().findFirst();
    }

    int countSince(String accountId, PointSource source, Instant from) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM point_grants WHERE account_id = ? AND source = ? AND granted_at >= ?",
                Integer.class, accountId, source.name(), Timestamp.from(from));
        return n == null ? 0 : n;
    }

    PointGrant insert(String accountId, PointSource source, String sourceRef, long amount, long journalId,
                      Instant at) {
        jdbc.update("""
                INSERT INTO point_grants (account_id, source, source_ref, amount, journal_id, granted_at)
                VALUES (?, ?, ?, ?, ?, ?)""", accountId, source.name(), sourceRef, amount, journalId,
                Timestamp.from(at));
        return find(source, sourceRef).orElseThrow();
    }
}
