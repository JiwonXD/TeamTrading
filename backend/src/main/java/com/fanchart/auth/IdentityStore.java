package com.fanchart.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
class IdentityStore {

    private final JdbcTemplate jdbc;

    IdentityStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<String> accountId(String provider, String subject) {
        return jdbc.queryForList("SELECT account_id FROM account_identities WHERE provider = ? AND subject = ?",
                String.class, provider, subject).stream().findFirst();
    }

    List<String> providersOf(String accountId) {
        return jdbc.queryForList("SELECT provider FROM account_identities WHERE account_id = ?", String.class, accountId);
    }

    void link(String provider, String subject, String accountId, Instant at) {
        jdbc.update("INSERT INTO account_identities (provider, subject, account_id, created_at) VALUES (?, ?, ?, ?)",
                provider, subject, accountId, Timestamp.from(at));
    }
}
