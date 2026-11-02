package com.teamtrading.offering;

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
class OfferingStore {

    private static final RowMapper<Offering> OFFERING = (rs, i) -> new Offering(rs.getLong("id"),
            rs.getString("team_id"), rs.getInt("round"), rs.getBoolean("first_listing"), rs.getLong("price"), rs.getLong("total_shares"),
            rs.getLong("max_per_account"), rs.getTimestamp("subscribe_from").toInstant(),
            rs.getTimestamp("subscribe_to").toInstant(), rs.getTimestamp("listing_at").toInstant(),
            Offering.Status.valueOf(rs.getString("status")), rs.getLong("allocated_shares"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("allocated_at") == null ? null : rs.getTimestamp("allocated_at").toInstant());

    private static final RowMapper<Subscription> SUBSCRIPTION = (rs, i) -> new Subscription(rs.getLong("offering_id"),
            rs.getString("account_id"), rs.getLong("quantity"), rs.getLong("deposit"),
            (Long) rs.getObject("allocated", Long.class), rs.getTimestamp("created_at").toInstant());

    /** 청약 현황. 경쟁률 = requested / totalShares */
    record Demand(long subscribers, long requested) {
    }

    private final JdbcTemplate jdbc;

    OfferingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    long insert(String teamId, int round, boolean firstListing, long price, long totalShares, long maxPerAccount, Instant from, Instant to,
                Instant listingAt, Instant now) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO offerings (team_id, round, first_listing, price, total_shares, max_per_account,
                                           subscribe_from, subscribe_to, listing_at, status, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'SUBSCRIBING', ?)""", new String[]{"id"});
            ps.setString(1, teamId);
            ps.setInt(2, round);
            ps.setBoolean(3, firstListing);
            ps.setLong(4, price);
            ps.setLong(5, totalShares);
            ps.setLong(6, maxPerAccount);
            ps.setTimestamp(7, Timestamp.from(from));
            ps.setTimestamp(8, Timestamp.from(to));
            ps.setTimestamp(9, Timestamp.from(listingAt));
            ps.setTimestamp(10, Timestamp.from(now));
            return ps;
        }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    Optional<Offering> find(long id) {
        return jdbc.query("SELECT * FROM offerings WHERE id = ?", OFFERING, id).stream().findFirst();
    }

    /** 공모 행을 잠근다. 청약·취소·배정은 모두 이 잠금 아래에서 상태와 시각을 확인한다 */
    Optional<Offering> lock(long id) {
        return jdbc.query("SELECT * FROM offerings WHERE id = ? FOR UPDATE", OFFERING, id).stream().findFirst();
    }

    List<Offering> recent(int limit) {
        return jdbc.query("SELECT * FROM offerings ORDER BY subscribe_from DESC, id DESC LIMIT ?", OFFERING, limit);
    }

    List<Offering> byTeam(String teamId) {
        return jdbc.query("SELECT * FROM offerings WHERE team_id = ? ORDER BY round", OFFERING, teamId);
    }

    /** 첫 상장 공모 중 아직 거래를 막아야 하는 것 */
    List<Offering> pendingListings(Instant now) {
        return jdbc.query("""
                SELECT * FROM offerings WHERE first_listing = TRUE
                AND (status = 'SUBSCRIBING' OR (status = 'ALLOCATED' AND listing_at > ?))""",
                OFFERING, Timestamp.from(now));
    }

    List<Long> due(Instant now) {
        return jdbc.queryForList("SELECT id FROM offerings WHERE status = 'SUBSCRIBING' AND subscribe_to <= ? ORDER BY id",
                Long.class, Timestamp.from(now));
    }

    int nextRound(String teamId) {
        Integer max = jdbc.queryForObject("SELECT MAX(round) FROM offerings WHERE team_id = ?", Integer.class, teamId);
        return max == null ? 1 : max + 1;
    }

    /** 그 구단 주식이 이미 있는지(지급·공모 배정 포함) */
    boolean sharesExist(String teamId) {
        Long n = jdbc.queryForObject("SELECT COALESCE(SUM(available + locked), 0) FROM positions WHERE team_id = ?",
                Long.class, teamId);
        return n != null && n > 0;
    }

    boolean hasOpen(String teamId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM offerings WHERE team_id = ? AND status = 'SUBSCRIBING'",
                Integer.class, teamId);
        return n != null && n > 0;
    }

    void markAllocated(long id, long allocatedShares, Instant now) {
        jdbc.update("UPDATE offerings SET status = 'ALLOCATED', allocated_shares = ?, allocated_at = ? WHERE id = ?",
                allocatedShares, Timestamp.from(now), id);
    }

    void markCanceled(long id) {
        jdbc.update("UPDATE offerings SET status = 'CANCELED' WHERE id = ?", id);
    }

    Demand demand(long offeringId) {
        return jdbc.queryForObject("SELECT COUNT(*), COALESCE(SUM(quantity), 0) FROM subscriptions WHERE offering_id = ?",
                (rs, i) -> new Demand(rs.getLong(1), rs.getLong(2)), offeringId);
    }

    Optional<Subscription> subscription(long offeringId, String accountId) {
        return jdbc.query("SELECT * FROM subscriptions WHERE offering_id = ? AND account_id = ?", SUBSCRIPTION,
                offeringId, accountId).stream().findFirst();
    }

    /** 계정 id 순서. 배정 때 계정 행을 이 순서로 갱신해 체결 정산과 교착되지 않게 한다 */
    List<Subscription> subscriptions(long offeringId) {
        return jdbc.query("SELECT * FROM subscriptions WHERE offering_id = ? ORDER BY account_id", SUBSCRIPTION,
                offeringId);
    }

    List<Subscription> mine(String accountId, int limit) {
        return jdbc.query("SELECT * FROM subscriptions WHERE account_id = ? ORDER BY created_at DESC LIMIT ?",
                SUBSCRIPTION, accountId, limit);
    }

    void insertSubscription(long offeringId, String accountId, long quantity, long deposit, Instant now) {
        jdbc.update("INSERT INTO subscriptions (offering_id, account_id, quantity, deposit, created_at) VALUES (?, ?, ?, ?, ?)",
                offeringId, accountId, quantity, deposit, Timestamp.from(now));
    }

    void deleteSubscription(long offeringId, String accountId) {
        jdbc.update("DELETE FROM subscriptions WHERE offering_id = ? AND account_id = ?", offeringId, accountId);
    }

    void setAllocated(long offeringId, String accountId, long allocated) {
        jdbc.update("UPDATE subscriptions SET allocated = ? WHERE offering_id = ? AND account_id = ?", allocated,
                offeringId, accountId);
    }
}
