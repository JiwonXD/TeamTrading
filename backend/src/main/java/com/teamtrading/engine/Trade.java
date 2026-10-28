package com.teamtrading.engine;

import java.time.Instant;

public record Trade(
        long id,
        String teamId,
        long price,
        long quantity,
        String buyerId,
        String sellerId,
        long buyOrderId,
        long sellOrderId,
        Side aggressor,
        Instant executedAt) {

    public boolean between(String a, String b) {
        return (buyerId.equals(a) && sellerId.equals(b)) || (buyerId.equals(b) && sellerId.equals(a));
    }
}
