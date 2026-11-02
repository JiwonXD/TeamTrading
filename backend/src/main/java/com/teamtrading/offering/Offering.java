package com.teamtrading.offering;

import java.time.Instant;

/**
 * 공모 한 건.
 *
 * @param round        구단별 회차
 * @param firstListing 등록할 때 그 구단 주식이 하나도 없었는지. 그러면 배정되고 상장 시각이 될 때까지 그 구단을 거래할 수 없다
 */
public record Offering(long id, String teamId, int round, boolean firstListing, long price, long totalShares, long maxPerAccount,
                       Instant subscribeFrom, Instant subscribeTo, Instant listingAt, Status status,
                       long allocatedShares, Instant createdAt, Instant allocatedAt) {

    public enum Status { SUBSCRIBING, ALLOCATED, CANCELED }

    boolean open(Instant now) {
        return status == Status.SUBSCRIBING && !now.isBefore(subscribeFrom) && now.isBefore(subscribeTo);
    }
}
