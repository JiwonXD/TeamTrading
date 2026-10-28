package com.teamtrading.account;

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
            rs.getString("nickname"), rs.getLong("initial_points"),
            rs.getLong("available_points"), rs.getLong("locked_points"));

    private static final RowMapper<Holding> HOLDING = (rs, i) -> new Holding(rs.getString("team_id"),
            rs.getLong("available"), rs.getLong("locked"), rs.getLong("average_price"));

    private final JdbcTemplate jdbc;

    public AccountStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Account create(String id, String nickname, long points, Instant now) {
        jdbc.update("INSERT INTO accounts (id, nickname, initial_points, available_points, created_at) VALUES (?, ?, ?, ?, ?)",
                id, nickname, points, points, Timestamp.from(now));
        return new Account(id, nickname, points, points, 0);
    }

    public Optional<Account> find(String id) {
        return jdbc.query("SELECT * FROM accounts WHERE id = ?", ACCOUNT, id).stream().findFirst();
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

    /** 시즌 시작 시 지급하는 초기 보유분. 거래 중에는 쓰지 않는다 */
    public void grantShares(String accountId, String teamId, long qty, long price) {
        applyShares(accountId, teamId, qty, 0, qty, qty * price);
    }
}
