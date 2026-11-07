package com.fanchart.post;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
class PostStore {

    private static final String SELECT = """
            SELECT p.*, a.nickname FROM posts p JOIN accounts a ON a.id = p.account_id""";

    private static final RowMapper<Post> POST = (rs, i) -> new Post(rs.getLong("id"), rs.getString("team_id"),
            rs.getString("account_id"), rs.getString("nickname"), rs.getString("body"), rs.getBoolean("holder"),
            Post.Status.valueOf(rs.getString("status")), rs.getInt("reports"), Post.Reward.valueOf(rs.getString("reward")),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    PostStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    long insert(String teamId, String accountId, String body, boolean holder, Instant now) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO posts (team_id, account_id, body, holder, status, created_at)
                    VALUES (?, ?, ?, ?, 'VISIBLE', ?)""", new String[]{"id"});
            ps.setString(1, teamId);
            ps.setString(2, accountId);
            ps.setString(3, body);
            ps.setBoolean(4, holder);
            ps.setTimestamp(5, Timestamp.from(now));
            return ps;
        }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    Optional<Post> find(long id) {
        return jdbc.query(SELECT + " WHERE p.id = ?", POST, id).stream().findFirst();
    }

    Optional<Post> lock(long id) {
        return jdbc.query(SELECT + " WHERE p.id = ? FOR UPDATE", POST, id).stream().findFirst();
    }

    /** 최신 글부터. before가 있으면 그 id보다 앞선 글만(더 보기) */
    List<Post> visible(String teamId, Long before, int limit) {
        return jdbc.query(SELECT + " WHERE p.team_id = ? AND p.status = 'VISIBLE' AND p.id < ? ORDER BY p.id DESC LIMIT ?",
                POST, teamId, before == null ? Long.MAX_VALUE : before, limit);
    }

    List<Post> byStatus(Post.Status status, int limit) {
        return jdbc.query(SELECT + " WHERE p.status = ? ORDER BY p.id DESC LIMIT ?", POST, status.name(), limit);
    }

    Optional<Instant> lastPostAt(String accountId) {
        Timestamp t = jdbc.queryForObject("SELECT MAX(created_at) FROM posts WHERE account_id = ?", Timestamp.class,
                accountId);
        return Optional.ofNullable(t).map(Timestamp::toInstant);
    }

    int countSince(String accountId, Instant since) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM posts WHERE account_id = ? AND created_at >= ?",
                Integer.class, accountId, Timestamp.from(since));
        return n == null ? 0 : n;
    }

    boolean sameBodySince(String accountId, String body, Instant since) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM posts WHERE account_id = ? AND body = ? AND created_at >= ?",
                Integer.class, accountId, body, Timestamp.from(since));
        return n != null && n > 0;
    }

    List<Long> rewardDue(Instant writtenBefore) {
        return jdbc.queryForList("SELECT id FROM posts WHERE reward = 'PENDING' AND created_at <= ? ORDER BY id",
                Long.class, Timestamp.from(writtenBefore));
    }

    void setStatus(long id, Post.Status status, Instant now) {
        jdbc.update("UPDATE posts SET status = ?, moderated_at = ? WHERE id = ?", status.name(), Timestamp.from(now), id);
    }

    void setReward(long id, Post.Reward reward) {
        jdbc.update("UPDATE posts SET reward = ? WHERE id = ?", reward.name(), id);
    }

    /** 신고를 남긴다. 이미 신고했으면 false */
    boolean report(long postId, String accountId, Post.ReportReason reason, Instant now) {
        try {
            jdbc.update("INSERT INTO post_reports (post_id, account_id, reason, created_at) VALUES (?, ?, ?, ?)",
                    postId, accountId, reason.name(), Timestamp.from(now));
        } catch (DuplicateKeyException e) {
            return false;
        }
        jdbc.update("UPDATE posts SET reports = reports + 1 WHERE id = ?", postId);
        return true;
    }
}
