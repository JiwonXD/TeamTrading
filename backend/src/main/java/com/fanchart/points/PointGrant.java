package com.fanchart.points;

import java.time.Instant;

public record PointGrant(long id, String accountId, PointSource source, String sourceRef, long amount,
                         long journalId, Instant grantedAt) {
}
