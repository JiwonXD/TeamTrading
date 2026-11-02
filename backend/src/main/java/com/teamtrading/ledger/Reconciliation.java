package com.teamtrading.ledger;

import com.teamtrading.ledger.Journal.Bucket;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 대사. 원장을 칸별로 합산한 값과 accounts·positions의 잔고를 비교한다.
 * 잔고는 조건부 UPDATE로, 원장은 전표로 따로 기록하므로 한쪽 코드가 틀리거나 DB를 직접 고치면 여기서 어긋난다.
 * 두 쪽을 한 읽기 트랜잭션에서 읽어 같은 시점의 스냅숏끼리 비교한다(InnoDB REPEATABLE READ).
 * 포인트 발행 총량도 원장의 발행처 칸과 발행 기록(point_grants)을 비교한다. 기록 없이 발행된 포인트가 있으면 어긋난다.
 */
@Service
public class Reconciliation {

    private final Ledger ledger;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate readTx;
    private final Clock clock;

    public Reconciliation(Ledger ledger, JdbcTemplate jdbc, PlatformTransactionManager txManager, Clock clock) {
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.clock = clock;
        this.readTx = new TransactionTemplate(txManager);
        readTx.setReadOnly(true);
        readTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public record Mismatch(String ownerId, String asset, Bucket bucket, long ledger, long balance) {
    }

    /** ledgerIssued: 원장 발행처에서 나간 포인트, recorded: 발행 기록 합, bySource: 경로별 발행 기록 합 */
    public record Issuance(long ledgerIssued, long recorded, Map<String, Long> bySource) {
        public boolean matches() {
            return ledgerIssued == recorded;
        }
    }

    public record Report(Instant checkedAt, boolean balanced, int slots, List<Mismatch> mismatches,
                         List<Long> unbalancedJournals, Issuance issuance) {
    }

    public Report run() {
        return readTx.execute(s -> {
            Map<Slot, Long> expected = ledger.balances();
            Map<Slot, Long> actual = balances();
            Set<Slot> slots = new HashSet<>(expected.keySet());
            slots.addAll(actual.keySet());
            List<Mismatch> mismatches = new ArrayList<>();
            for (Slot slot : slots) {
                long l = expected.getOrDefault(slot, 0L);
                long b = actual.getOrDefault(slot, 0L);
                if (l != b) mismatches.add(new Mismatch(slot.ownerId(), slot.asset(), slot.bucket(), l, b));
            }
            mismatches.sort(Comparator.comparing(Mismatch::ownerId).thenComparing(Mismatch::asset)
                    .thenComparing(Mismatch::bucket));
            List<Long> unbalanced = ledger.unbalancedJournals();
            Issuance issuance = issuance();
            return new Report(clock.instant(), mismatches.isEmpty() && unbalanced.isEmpty() && issuance.matches(),
                    slots.size(), mismatches, unbalanced, issuance);
        });
    }

    private Map<Slot, Long> balances() {
        Map<Slot, Long> balances = new HashMap<>();
        jdbc.query("SELECT id, available_points, locked_points FROM accounts", rs -> {
            put(balances, new Slot(rs.getString(1), Journal.POINT, Bucket.AVAILABLE), rs.getLong(2));
            put(balances, new Slot(rs.getString(1), Journal.POINT, Bucket.LOCKED), rs.getLong(3));
        });
        jdbc.query("SELECT account_id, team_id, available, locked FROM positions", rs -> {
            put(balances, new Slot(rs.getString(1), rs.getString(2), Bucket.AVAILABLE), rs.getLong(3));
            put(balances, new Slot(rs.getString(1), rs.getString(2), Bucket.LOCKED), rs.getLong(4));
        });
        return balances;
    }

    private Issuance issuance() {
        Long issued = jdbc.queryForObject("""
                SELECT COALESCE(-SUM(amount), 0) FROM ledger_entries
                WHERE owner_id = ? AND asset = ? AND bucket = ?""", Long.class,
                Journal.SYSTEM, Journal.POINT, Bucket.ISSUED.name());
        Map<String, Long> bySource = new TreeMap<>();
        jdbc.query("SELECT source, SUM(amount) FROM point_grants GROUP BY source",
                rs -> {
                    bySource.put(rs.getString(1), rs.getLong(2));
                });
        long recorded = bySource.values().stream().mapToLong(Long::longValue).sum();
        return new Issuance(issued == null ? 0 : issued, recorded, bySource);
    }

    private static void put(Map<Slot, Long> balances, Slot slot, long amount) {
        if (amount != 0) balances.put(slot, amount);
    }
}
