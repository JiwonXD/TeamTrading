package com.teamtrading.fds;

import java.time.Instant;

public record Alert(AlertKind kind, String teamId, long tradeId, String accountA, String accountB,
                    String message, Instant detectedAt) {
}
