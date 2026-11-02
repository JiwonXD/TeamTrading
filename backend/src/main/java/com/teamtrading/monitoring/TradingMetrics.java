package com.teamtrading.monitoring;

import com.teamtrading.engine.Trade;
import com.teamtrading.trading.MarketUpdate;
import com.teamtrading.trading.TradingService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 매매 지표. 체결 수·수량·대금은 구단별 카운터, 거래가 멈춘 구단 수는 게이지.
 * 체결은 TradingService가 종목 락 안에서 발행하는 업데이트로 센다. 카운터 증가만 하므로 락을 오래 잡지 않는다
 */
@Component
class TradingMetrics {

    private final MeterRegistry registry;
    private final Map<String, Counter[]> byTeam = new ConcurrentHashMap<>();

    TradingMetrics(MeterRegistry registry, TradingService trading) {
        this.registry = registry;
        Gauge.builder("teamtrading.markets.halted", trading, t -> t.haltedTeams().size())
                .description("복구에 실패해 거래를 멈춘 구단 수").register(registry);
    }

    @EventListener
    void onUpdate(MarketUpdate update) {
        if (update.trades().isEmpty()) return;
        Counter[] c = byTeam.computeIfAbsent(update.teamId(), team -> new Counter[]{
                Counter.builder("teamtrading.trades").tag("team", team).description("체결 건수").register(registry),
                Counter.builder("teamtrading.trade.shares").tag("team", team).description("체결 수량").register(registry),
                Counter.builder("teamtrading.trade.points").tag("team", team).description("체결 대금").register(registry)});
        for (Trade t : update.trades()) {
            c[0].increment();
            c[1].increment(t.quantity());
            c[2].increment((double) t.price() * t.quantity());
        }
    }
}
