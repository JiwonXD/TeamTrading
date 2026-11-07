package com.teamtrading.ad;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
class AdSessionStore {

    private static final RowMapper<AdSession> SESSION = (rs, i) -> {
        Timestamp done = rs.getTimestamp("completed_at");
        return new AdSession(rs.getString("id"), rs.getString("account_id"),
                AdSession.Status.valueOf(rs.getString("status")), rs.getTimestamp("created_at").toInstant(),
                done == null ? null : done.toInstant(), rs.getString("transaction_ref"));
    };

    private final JdbcTemplate jdbc;

    AdSessionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insert(String id, String accountId, Instant at) {
        jdbc.update("INSERT INTO ad_sessions (id, account_id, status, created_at) VALUES (?, ?, 'PENDING', ?)", id,
                accountId, Timestamp.from(at));
    }

    Optional<AdSession> find(String id) {
        return jdbc.query("SELECT * FROM ad_sessions WHERE id = ?", SESSION, id).stream().findFirst();
    }

    Optional<AdSession> lock(String id) {
        return jdbc.query("SELECT * FROM ad_sessions WHERE id = ? FOR UPDATE", SESSION, id).stream().findFirst();
    }

    int countSince(String accountId, Instant from) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM ad_sessions WHERE account_id = ? AND created_at >= ?",
                Integer.class, accountId, Timestamp.from(from));
        return n == null ? 0 : n;
    }

    void complete(String id, AdSession.Status status, String transactionRef, Instant at) {
        jdbc.update("UPDATE ad_sessions SET status = ?, transaction_ref = ?, completed_at = ? WHERE id = ?",
                status.name(), transactionRef, Timestamp.from(at), id);
    }
}
