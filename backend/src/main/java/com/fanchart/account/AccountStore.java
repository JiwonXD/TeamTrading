package com.fanchart.account;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 포인트와 보유 수량. 잔고를 읽고 판단한 뒤 차감하면 동시 요청에서 갱신이 유실되므로,
 * 판단과 변경을 한 문장의 조건부 UPDATE로 하고 영향 행 수로 성공 여부를 본다.
 * 계정끼리 직접 옮기는 쿼리는 없다. 포인트는 주문에 묶였다가 체결로만 상대에게 간다.
 */
@Repository
public class AccountStore {

    private static final RowMapper<Account> ACCOUNT = (rs, i) -> new Account(rs.getString("id"),
            rs.getString("nickname"), rs.getLong("initial_value"),
            rs.getLong("available_points"), rs.getLong("locked_points"),
            Optional.ofNullable(rs.getTimestamp("nickname_changed_at")).map(Timestamp::toInstant).orElse(null),
            Optional.ofNullable(rs.getTimestamp("withdrawn_at")).map(Timestamp::toInstant).orElse(null));

    private static final RowMapper<Holding> HOLDING = (rs, i) -> new Holding(rs.getString("team_id"),
            rs.getLong("available"), rs.getLong("locked"), rs.getLong("average_price"));

    private final JdbcTemplate jdbc;

    public AccountStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 잔고 0으로 만든다. 가입 지급은 PointIssuer가 발행 기록과 함께 넣는다 */
    public void create(String id, String nickname, Instant now) {
        jdbc.update("""
                INSERT INTO accounts (id, nickname, initial_points, initial_value, available_points, created_at)
                VALUES (?, ?, 0, 0, 0, ?)""", id, nickname, Timestamp.from(now));
    }

    /** create와 같되, 닉네임이 이미 쓰이고 있으면 아무것도 하지 않고 false. 트랜잭션은 그대로 이어 쓸 수 있다 */
    public boolean tryCreate(String id, String nickname, Instant now) {
        try {
            create(id, nickname, now);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 닉네임을 바꾼다. 마지막 변경이 notChangedSince 이후면 바꾸지 않고 false.
     * 다른 계정이 쓰는 닉네임이면 DuplicateKeyException
     */
    public boolean changeNickname(String id, String nickname, Instant now, Instant notChangedSince) {
        return jdbc.update("""
                UPDATE accounts SET nickname = ?, nickname_changed_at = ?
                WHERE id = ? AND (nickname_changed_at IS NULL OR nickname_changed_at <= ?)""",
                nickname, Timestamp.from(now), id, Timestamp.from(notChangedSince)) == 1;
    }

    /**
     * 탈퇴로 표시하고 닉네임을 바꾼다. 이미 탈퇴했으면 false.
     * 바꿀 닉네임을 다른 계정이 쓰고 있으면 DuplicateKeyException(그 문장만 실패하고 트랜잭션은 이어 쓸 수 있다)
     */
    public boolean markWithdrawn(String id, String nickname, Instant now) {
        return jdbc.update("UPDATE accounts SET nickname = ?, withdrawn_at = ? WHERE id = ? AND withdrawn_at IS NULL",
                nickname, Timestamp.from(now), id) == 1;
    }

    public List<String> withdrawnIds() {
        return jdbc.queryForList("SELECT id FROM accounts WHERE withdrawn_at IS NOT NULL", String.class);
    }

    public Optional<Account> find(String id) {
        return jdbc.query("SELECT * FROM accounts WHERE id = ?", ACCOUNT, id).stream().findFirst();
    }

    /** 트랜잭션이 끝날 때까지 계정 행을 잠근다. 계정이 없으면 false */
    public boolean lockRow(String id) {
        return !jdbc.queryForList("SELECT id FROM accounts WHERE id = ? FOR UPDATE", String.class, id).isEmpty();
    }

    public List<Account> all() {
        return jdbc.query("SELECT * FROM accounts", ACCOUNT);
    }

    public List<Holding> holdings(String accountId) {
        return jdbc.query("SELECT * FROM positions WHERE account_id = ? AND available + locked > 0 ORDER BY team_id",
                HOLDING, accountId);
    }

    public long totalShares(String accountId, String teamId) {
        return jdbc.query("SELECT available + locked FROM positions WHERE account_id = ? AND team_id = ?",
                (rs, i) -> rs.getLong(1), accountId, teamId).stream().findFirst().orElse(0L);
    }

    /** 주문 가능 포인트가 충분할 때만 묶는다 */
    public boolean lockPoints(String accountId, long amount) {
        return jdbc.update("""
                UPDATE accounts SET available_points = available_points - ?, locked_points = locked_points + ?
                WHERE id = ? AND available_points >= ?""", amount, amount, accountId, amount) == 1;
    }

    /** 시즌 초 주식 지급분을 수익률 기준에 더한다 */
    public void addInitialValue(String accountId, long amount) {
        jdbc.update("UPDATE accounts SET initial_value = initial_value + ? WHERE id = ?", amount, accountId);
    }

    /** 판매 가능 수량이 충분할 때만 묶는다 */
    public boolean lockShares(String accountId, String teamId, long qty) {
        return jdbc.update("""
                UPDATE positions SET available = available - ?, locked = locked + ?
                WHERE account_id = ? AND team_id = ? AND available >= ?""", qty, qty, accountId, teamId, qty) == 1;
    }

    /** 체결·취소 정산. 묶인 포인트보다 많이 풀려고 하면 실패한다 */
    public void applyPoints(String accountId, long availableDelta, long lockedDelta) {
        int rows = jdbc.update("""
                UPDATE accounts SET available_points = available_points + ?, locked_points = locked_points + ?
                WHERE id = ? AND available_points + ? >= 0 AND locked_points + ? >= 0""",
                availableDelta, lockedDelta, accountId, availableDelta, lockedDelta);
        if (rows != 1) throw new IllegalStateException("point balance invariant broken: " + accountId);
    }

    /**
     * 보유 수량 정산. 같은 종목의 보유 변경은 모두 종목 락 안에서 일어나므로
     * 읽고 평균 단가를 계산한 뒤 쓰는 사이에 다른 변경이 끼어들지 않는다.
     */
    public void applyShares(String accountId, String teamId, long availableDelta, long lockedDelta,
                            long boughtQty, long boughtCost) {
        List<long[]> rows = jdbc.query(
                "SELECT available, locked, average_price FROM positions WHERE account_id = ? AND team_id = ? FOR UPDATE",
                (rs, i) -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)}, accountId, teamId);
        if (rows.isEmpty()) {
            if (lockedDelta != 0 || availableDelta != boughtQty)
                throw new IllegalStateException("share balance invariant broken: " + accountId);
            jdbc.update("INSERT INTO positions (account_id, team_id, available, average_price) VALUES (?, ?, ?, ?)",
                    accountId, teamId, boughtQty, Math.round((double) boughtCost / boughtQty));
            return;
        }
        long[] p = rows.getFirst();
        long total = p[0] + p[1];
        long average = boughtQty == 0 ? p[2]
                : Math.round((p[2] * (double) total + boughtCost) / (total + boughtQty));
        int updated = jdbc.update("""
                UPDATE positions SET available = available + ?, locked = locked + ?, average_price = ?
                WHERE account_id = ? AND team_id = ? AND available + ? >= 0 AND locked + ? >= 0""",
                availableDelta, lockedDelta, average, accountId, teamId, availableDelta, lockedDelta);
        if (updated != 1) throw new IllegalStateException("share balance invariant broken: " + accountId);
    }
}
