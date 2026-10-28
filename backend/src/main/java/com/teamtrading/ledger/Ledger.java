package com.teamtrading.ledger;

import com.teamtrading.ledger.Journal.Bucket;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 원장 기록. 잔고를 바꾸는 트랜잭션 안에서 함께 호출해야 둘이 같이 커밋되거나 같이 롤백된다 */
@Repository
public class Ledger {

    private final JdbcTemplate jdbc;

    public Ledger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void post(Journal journal, Instant at) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO ledger_journals (kind, order_id, trade_id, created_at) VALUES (?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, journal.kind().name());
            ps.setObject(2, journal.orderId(), Types.BIGINT);
            ps.setObject(3, journal.tradeId(), Types.BIGINT);
            ps.setTimestamp(4, Timestamp.from(at));
            return ps;
        }, key);
        long journalId = Objects.requireNonNull(key.getKey()).longValue();
        jdbc.batchUpdate("INSERT INTO ledger_entries (journal_id, owner_id, asset, bucket, amount) VALUES (?, ?, ?, ?, ?)",
                journal.entries(), journal.entries().size(), (ps, e) -> {
                    ps.setLong(1, journalId);
                    ps.setString(2, e.ownerId());
                    ps.setString(3, e.asset());
                    ps.setString(4, e.bucket().name());
                    ps.setLong(5, e.amount());
                });
    }

    /** 계정별 칸마다 원장 합계. 발행처는 빼고 0인 칸도 뺀다 */
    public Map<Slot, Long> balances() {
        Map<Slot, Long> sums = new HashMap<>();
        jdbc.query("""
                SELECT owner_id, asset, bucket, SUM(amount) FROM ledger_entries
                WHERE owner_id <> ? GROUP BY owner_id, asset, bucket HAVING SUM(amount) <> 0""",
                rs -> {
                    sums.put(new Slot(rs.getString(1), rs.getString(2), Bucket.valueOf(rs.getString(3))), rs.getLong(4));
                }, Journal.SYSTEM);
        return sums;
    }

    /** 자산별 합이 0이 아닌 전표. 코드로는 만들 수 없으니 DB를 직접 고친 흔적이다 */
    public List<Long> unbalancedJournals() {
        return jdbc.queryForList("""
                SELECT DISTINCT journal_id FROM (
                    SELECT journal_id FROM ledger_entries GROUP BY journal_id, asset HAVING SUM(amount) <> 0
                ) t ORDER BY journal_id""", Long.class);
    }
}
