package com.teamtrading.fds;

import com.teamtrading.engine.Trade;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 이상거래 탐지. 포인트를 특정 계정에 몰아주는 우회 이전 패턴을 잡는 것이 목적이다. */
public final class FdsDetector {

    public static final double DEVIATION_RATE = 0.10;
    public static final Duration PAIR_WINDOW = Duration.ofMinutes(10);
    public static final int PAIR_THRESHOLD = 5;

    private FdsDetector() {
    }

    /**
     * @param prevPrice 직전 체결가
     * @param recent    같은 종목의 이전 체결 내역
     */
    public static List<Alert> detect(Trade trade, long prevPrice, List<Trade> recent) {
        List<Alert> alerts = new ArrayList<>();

        double deviation = Math.abs(trade.price() - prevPrice) / (double) prevPrice;
        if (deviation >= DEVIATION_RATE) {
            alerts.add(alert(AlertKind.PRICE_DEVIATION, trade,
                    "직전 체결가 대비 %.1f%% 벗어난 가격에 체결".formatted(deviation * 100)));
        }

        long samePair = recent.stream()
                .filter(t -> !t.executedAt().isBefore(trade.executedAt().minus(PAIR_WINDOW)))
                .filter(t -> t.between(trade.buyerId(), trade.sellerId()))
                .count();
        // 임계치를 넘는 순간 한 번만 기록한다
        if (samePair + 1 == PAIR_THRESHOLD) {
            alerts.add(alert(AlertKind.REPEATED_COUNTERPARTY, trade,
                    "%d분 안에 같은 상대와 %d회 이상 체결".formatted(PAIR_WINDOW.toMinutes(), PAIR_THRESHOLD)));
        }
        return alerts;
    }

    private static Alert alert(AlertKind kind, Trade t, String message) {
        return new Alert(kind, t.teamId(), t.id(), t.buyerId(), t.sellerId(), message, t.executedAt());
    }
}
