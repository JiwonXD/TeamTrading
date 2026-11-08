package com.fanchart.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
class PasswordLoginStore {

    record Row(String loginId, String accountId, String passwordHash, String email, Instant lockedUntil) {
    }

    private final JdbcTemplate jdbc;

    PasswordLoginStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Row> find(String loginId) {
        return jdbc.query("SELECT login_id, account_id, password_hash, email, locked_until FROM password_logins WHERE login_id = ?",
                (rs, i) -> {
                    Timestamp locked = rs.getTimestamp("locked_until");
                    return new Row(rs.getString("login_id"), rs.getString("account_id"), rs.getString("password_hash"),
                            rs.getString("email"), locked == null ? null : locked.toInstant());
                }, loginId).stream().findFirst();
    }

    Optional<String> loginIdOf(String accountId) {
        return jdbc.queryForList("SELECT login_id FROM password_logins WHERE account_id = ?", String.class, accountId)
                .stream().findFirst();
    }

    boolean exists(String loginId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM password_logins WHERE login_id = ?", Integer.class, loginId);
        return n != null && n > 0;
    }

    void create(String loginId, String accountId, String passwordHash, String email, Instant at) {
        jdbc.update("INSERT INTO password_logins (login_id, account_id, password_hash, email, created_at) VALUES (?, ?, ?, ?, ?)",
                loginId, accountId, passwordHash, email, Timestamp.from(at));
    }

    /**
     * 비밀번호를 맞춰 보기 전에 시도 한 번을 먼저 센다. 잠겨 있으면 0을 돌려준다.
     * 세는 것과 잠그는 것이 한 문장이라, 같은 아이디로 동시에 요청을 쏟아부어도 잠기기 전까지 maxFailures번만 맞춰 볼 수 있다.
     * 맞으면 {@link #clearFailures}로 되돌린다. locked_until을 먼저 계산해야 MySQL(앞에서 바꾼 값을 뒤에서 읽는다)과
     * H2(원래 값을 읽는다)가 같은 결과를 낸다.
     */
    int reserveAttempt(String loginId, Instant now, int maxFailures, Instant lockUntil) {
        return jdbc.update("""
                        UPDATE password_logins
                        SET locked_until = CASE WHEN failed_attempts + 1 >= ? THEN ? ELSE NULL END,
                            failed_attempts = CASE WHEN failed_attempts + 1 >= ? THEN 0 ELSE failed_attempts + 1 END
                        WHERE login_id = ? AND (locked_until IS NULL OR locked_until <= ?)""",
                maxFailures, Timestamp.from(lockUntil), maxFailures, loginId, Timestamp.from(now));
    }

    void clearFailures(String loginId) {
        jdbc.update("UPDATE password_logins SET failed_attempts = 0, locked_until = NULL WHERE login_id = ?", loginId);
    }

    void changePassword(String loginId, String passwordHash) {
        jdbc.update("UPDATE password_logins SET password_hash = ?, failed_attempts = 0, locked_until = NULL WHERE login_id = ?",
                passwordHash, loginId);
    }

    // ---------- 재설정 토큰 ----------

    Optional<Instant> lastTokenAt(String loginId) {
        return jdbc.queryForList("SELECT MAX(created_at) FROM password_reset_tokens WHERE login_id = ?", Timestamp.class, loginId)
                .stream().filter(t -> t != null).map(Timestamp::toInstant).findFirst();
    }

    /** 새 토큰을 남기고 같은 아이디의 이전 토큰은 지운다. 마지막에 받은 메일의 링크만 쓸 수 있다 */
    void replaceToken(String loginId, String tokenHash, Instant at, Instant expiresAt) {
        jdbc.update("DELETE FROM password_reset_tokens WHERE login_id = ?", loginId);
        jdbc.update("INSERT INTO password_reset_tokens (token_hash, login_id, created_at, expires_at) VALUES (?, ?, ?, ?)",
                tokenHash, loginId, Timestamp.from(at), Timestamp.from(expiresAt));
    }

    /** 쓰지 않았고 만료 전인 토큰의 아이디 */
    Optional<String> findTokenLogin(String tokenHash, Instant now) {
        return jdbc.queryForList("SELECT login_id FROM password_reset_tokens WHERE token_hash = ? AND used_at IS NULL AND expires_at > ?",
                String.class, tokenHash, Timestamp.from(now)).stream().findFirst();
    }

    /** 쓰지 않았고 만료 전인 토큰을 쓴 것으로 표시하고 그 아이디를 돌려준다. 같은 토큰이 동시에 두 번 와도 한 번만 성공한다 */
    Optional<String> useToken(String tokenHash, Instant now) {
        int n = jdbc.update("UPDATE password_reset_tokens SET used_at = ? WHERE token_hash = ? AND used_at IS NULL AND expires_at > ?",
                Timestamp.from(now), tokenHash, Timestamp.from(now));
        if (n == 0) return Optional.empty();
        return jdbc.queryForList("SELECT login_id FROM password_reset_tokens WHERE token_hash = ?", String.class, tokenHash)
                .stream().findFirst();
    }
}
