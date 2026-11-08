package com.fanchart.trading;

import com.fanchart.engine.Side;
import com.fanchart.engine.Trade;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CandlesTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** DB 없이 체결 목록으로 TradeStore.before를 흉내 낸다 */
    static class FakeStore extends TradeStore {
        final List<Trade> trades = new ArrayList<>();

        FakeStore() {
            super(null);
        }

        void add(String kst, long price, long qty) {
            trades.add(new Trade(trades.size() + 1, "x", price, qty, "b", "s", 1, 2, Side.BUY,
                    LocalDateTime.parse(kst).atZone(KST).toInstant()));
        }

        @Override
        public List<Trade> before(String teamId, Instant at, long id, int limit) {
            return trades.stream()
                    .filter(t -> t.executedAt().isBefore(at) || (t.executedAt().equals(at) && t.id() < id))
                    .sorted(Comparator.comparing(Trade::executedAt).thenComparing(Trade::id).reversed())
                    .limit(limit).toList();
        }
    }

    private static Instant kst(String s) {
        return LocalDateTime.parse(s).atZone(KST).toInstant();
    }

    @Test
    void groupsTradesIntoOhlcvAndSkipsEmptyIntervals() {
        FakeStore store = new FakeStore();
        store.add("2026-11-08T10:01:00", 100, 2);
        store.add("2026-11-08T10:02:00", 120, 1);
        store.add("2026-11-08T10:03:00", 90, 3);
        store.add("2026-11-08T10:04:59", 110, 4);
        store.add("2026-11-08T10:20:00", 130, 5);

        var page = Candles.load(store, "x", CandleUnit.M5, null, 200);

        assertThat(page.candles()).containsExactly(
                new Candles.Candle(kst("2026-11-08T10:00:00"), 100, 120, 90, 110, 10),
                new Candles.Candle(kst("2026-11-08T10:20:00"), 130, 130, 130, 130, 5));
        assertThat(page.lastTradeId()).isEqualTo(5);
        assertThat(page.more()).isFalse();
    }

    @Test
    void usesKoreanCalendarBoundaries() {
        assertThat(CandleUnit.DAY.start(kst("2026-11-08T23:59:59"))).isEqualTo(kst("2026-11-08T00:00:00"));
        assertThat(CandleUnit.DAY.start(kst("2026-11-09T00:00:01"))).isEqualTo(kst("2026-11-09T00:00:00"));
        // 2026-11-08은 일요일이라 그 주 월요일은 11-02
        assertThat(CandleUnit.WEEK.start(kst("2026-11-08T12:00:00"))).isEqualTo(kst("2026-11-02T00:00:00"));
        assertThat(CandleUnit.MONTH.start(kst("2026-11-08T12:00:00"))).isEqualTo(kst("2026-11-01T00:00:00"));
        assertThat(CandleUnit.YEAR.start(kst("2026-11-08T12:00:00"))).isEqualTo(kst("2026-01-01T00:00:00"));
        // 240분 봉은 자정부터 4시간씩
        assertThat(CandleUnit.M240.start(kst("2026-11-08T09:30:00"))).isEqualTo(kst("2026-11-08T08:00:00"));
    }

    @Test
    void pagesBackwardsFromBefore() {
        FakeStore store = new FakeStore();
        for (int m = 0; m < 5; m++) store.add("2026-11-08T10:0" + m + ":30", 100 + m, 1);

        var latest = Candles.load(store, "x", CandleUnit.M1, null, 2);
        assertThat(latest.candles()).extracting(Candles.Candle::close).containsExactly(103L, 104L);
        assertThat(latest.more()).isTrue();

        var older = Candles.load(store, "x", CandleUnit.M1, latest.candles().getFirst().time(), 2);
        assertThat(older.candles()).extracting(Candles.Candle::close).containsExactly(101L, 102L);
        assertThat(older.more()).isTrue();

        var oldest = Candles.load(store, "x", CandleUnit.M1, older.candles().getFirst().time(), 2);
        assertThat(oldest.candles()).extracting(Candles.Candle::close).containsExactly(100L);
        assertThat(oldest.more()).isFalse();
    }

    @Test
    void readsAcrossChunksWithoutLosingTrades() {
        FakeStore store = new FakeStore();
        // 같은 시각 체결이 청크 경계에 걸쳐도 빠지거나 겹치지 않아야 한다
        for (int i = 0; i < 4_500; i++) store.add("2026-11-08T10:00:00", 100, 1);
        store.add("2026-11-08T10:01:00", 200, 1);

        var page = Candles.load(store, "x", CandleUnit.DAY, null, 10);
        assertThat(page.candles()).containsExactly(
                new Candles.Candle(kst("2026-11-08T00:00:00"), 100, 200, 100, 200, 4_501));
    }
}
