package com.teamtrading.fds;

import com.teamtrading.engine.Side;
import com.teamtrading.engine.Trade;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FdsDetectorTest {

    private long seq;

    private Trade trade(String buyer, String seller, long price, long second) {
        return new Trade(++seq, "t", price, 1, buyer, seller, 0, 0, Side.BUY, Instant.ofEpochSecond(second));
    }

    @Test
    void flagsTradesFarFromLastPrice() {
        assertThat(FdsDetector.detect(trade("a", "b", 11_500, 0), 10_000, List.of()))
                .extracting(Alert::kind).containsExactly(AlertKind.PRICE_DEVIATION);
        assertThat(FdsDetector.detect(trade("a", "b", 10_500, 0), 10_000, List.of())).isEmpty();
    }

    @Test
    void flagsRepeatedCounterpartyOnceWhenThresholdIsReached() {
        List<Trade> recent = new ArrayList<>();
        for (int i = 0; i < FdsDetector.PAIR_THRESHOLD - 1; i++)
            recent.add(i % 2 == 0 ? trade("a", "b", 10_000, i) : trade("b", "a", 10_000, i));

        Trade hit = trade("a", "b", 10_000, 100);
        assertThat(FdsDetector.detect(hit, 10_000, recent))
                .extracting(Alert::kind).containsExactly(AlertKind.REPEATED_COUNTERPARTY);

        recent.add(hit);
        assertThat(FdsDetector.detect(trade("a", "b", 10_000, 101), 10_000, recent)).isEmpty();
    }

    @Test
    void ignoresPairTradesOutsideWindow() {
        List<Trade> old = new ArrayList<>();
        for (int i = 0; i < FdsDetector.PAIR_THRESHOLD - 1; i++) old.add(trade("a", "b", 10_000, i));
        long later = FdsDetector.PAIR_WINDOW.toSeconds() + 100;
        assertThat(FdsDetector.detect(trade("a", "b", 10_000, later), 10_000, old)).isEmpty();
    }
}
