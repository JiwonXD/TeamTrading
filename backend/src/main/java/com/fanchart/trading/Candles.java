package com.fanchart.trading;

import com.fanchart.engine.Trade;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 체결 기록을 봉으로 묶는다. 봉을 따로 저장하지 않고 요청 때마다 체결 기록에서 계산하므로 원장과 어긋날 수 없다.
 * 체결이 없던 구간은 봉을 만들지 않는다.
 */
public final class Candles {

    public record Candle(Instant time, long open, long high, long low, long close, long volume) {
    }

    /**
     * @param lastTradeId 이 결과에 들어간 가장 큰 체결 번호. 화면은 이보다 큰 실시간 체결만 마지막 봉에 더한다
     * @param more        더 이전 봉이 있는지
     */
    public record Page(List<Candle> candles, long lastTradeId, boolean more) {
    }

    private static final int CHUNK = 2_000;
    private static final Instant END = Instant.parse("3000-01-01T00:00:00Z");

    private Candles() {
    }

    /** before(봉 시작 시각) 이전 체결로 만든 최근 봉 최대 limit개. 오래된 봉이 앞 */
    static Page load(TradeStore store, String teamId, CandleUnit unit, Instant before, int limit) {
        List<Candle> out = new ArrayList<>();
        Instant cursorAt = before == null ? END : before;
        long cursorId = 0;
        long lastTradeId = 0;
        Instant bucket = null;
        long open = 0, high = 0, low = 0, close = 0, volume = 0;
        boolean more = false;
        scan:
        while (true) {
            List<Trade> chunk = store.before(teamId, cursorAt, cursorId, CHUNK);
            // 최신 체결부터 거꾸로 읽으므로 봉에서 처음 만난 체결이 종가, 마지막에 만난 체결이 시가다
            for (Trade t : chunk) {
                Instant b = unit.start(t.executedAt());
                if (!b.equals(bucket)) {
                    if (bucket != null) out.add(new Candle(bucket, open, high, low, close, volume));
                    if (out.size() == limit) {
                        more = true;
                        bucket = null;
                        break scan;
                    }
                    bucket = b;
                    close = high = low = t.price();
                    volume = 0;
                }
                lastTradeId = Math.max(lastTradeId, t.id());
                open = t.price();
                high = Math.max(high, t.price());
                low = Math.min(low, t.price());
                volume += t.quantity();
            }
            if (chunk.size() < CHUNK) break;
            Trade oldest = chunk.getLast();
            cursorAt = oldest.executedAt();
            cursorId = oldest.id();
        }
        if (bucket != null) out.add(new Candle(bucket, open, high, low, close, volume));
        Collections.reverse(out);
        return new Page(out, lastTradeId, more);
    }
}
