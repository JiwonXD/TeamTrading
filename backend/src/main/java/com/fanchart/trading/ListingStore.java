package com.fanchart.trading;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;

/**
 * 첫 상장 공모 상태를 읽어 기동 시 거래 차단과 동시호가를 되살린다.
 * 청약 중이면 Instant.MAX(주문을 받지 않음), 배정됐으면 상장 시각(그 전까지 동시호가).
 * 상장 시각이 지났으면 기동 직후 단일가 매매를 다시 시도한다. 이미 체결을 마친 구단이면 교차하는 주문이 없어 아무것도 체결되지 않는다.
 */
@Repository
class ListingStore {

    private final JdbcTemplate jdbc;

    ListingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 운영자가 등록했지만 아직 주식이 없고 첫 상장 공모도 진행 중이 아닌 구단. 공모 전까지 거래를 막는다.
     * 공모가 청약 중이거나 배정됐으면 holds()가 정한다
     */
    Set<String> awaitingFirstListing() {
        return new HashSet<>(jdbc.queryForList("""
                SELECT t.id FROM teams t
                WHERE t.ipo_required = TRUE
                  AND NOT EXISTS (SELECT 1 FROM positions p WHERE p.team_id = t.id AND p.available + p.locked > 0)
                  AND NOT EXISTS (SELECT 1 FROM offerings o WHERE o.team_id = t.id AND o.first_listing = TRUE
                                  AND o.status IN ('SUBSCRIBING', 'ALLOCATED'))""", String.class));
    }

    Map<String, Instant> holds() {
        Map<String, Instant> out = new HashMap<>();
        jdbc.query("""
                SELECT team_id, status, listing_at FROM offerings
                WHERE first_listing = TRUE AND status IN ('SUBSCRIBING', 'ALLOCATED')""",
                rs -> {
                    Instant at = "SUBSCRIBING".equals(rs.getString(2)) ? Instant.MAX : rs.getTimestamp(3).toInstant();
                    out.merge(rs.getString(1), at, (a, b) -> a.isAfter(b) ? a : b);
                });
        return out;
    }
}
