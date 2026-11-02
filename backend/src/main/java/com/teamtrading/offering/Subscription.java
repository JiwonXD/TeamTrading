package com.teamtrading.offering;

import java.time.Instant;

/** @param allocated 배정 수량. 배정 전이면 null */
public record Subscription(long offeringId, String accountId, long quantity, long deposit, Long allocated,
                           Instant createdAt) {
}
