package com.fanchart.api;

import com.fanchart.api.ApiDtos.OrderBookResponse;
import com.fanchart.api.ApiDtos.QuoteResponse;
import com.fanchart.api.ApiDtos.TradeResponse;
import com.fanchart.trading.CandleUnit;
import com.fanchart.trading.Candles;
import com.fanchart.trading.TradingService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/teams")
class MarketController {

    private final TradingService trading;

    MarketController(TradingService trading) {
        this.trading = trading;
    }

    @GetMapping
    List<QuoteResponse> quotes() {
        return trading.quotes().stream().map(QuoteResponse::of).toList();
    }

    @GetMapping("/{teamId}")
    QuoteResponse quote(@PathVariable String teamId) {
        return QuoteResponse.of(trading.quote(teamId));
    }

    @GetMapping("/{teamId}/orderbook")
    OrderBookResponse orderBook(@PathVariable String teamId, @RequestParam(defaultValue = "10") int depth) {
        var s = trading.orderBook(teamId, Math.min(depth, 20));
        return new OrderBookResponse(s.asks(), s.bids(), s.lastPrice(), s.epoch(), s.seq());
    }

    @GetMapping("/{teamId}/trades")
    List<TradeResponse> trades(@PathVariable String teamId, @RequestParam(defaultValue = "30") int limit) {
        return trading.trades(teamId, Math.min(limit, 100)).stream().map(TradeResponse::of).toList();
    }

    /** 차트 봉. unit: 1m·3m·5m·10m·15m·30m·60m·120m·240m·day·week·month·year, before: 이 시각(ms) 이전 봉만 */
    @GetMapping("/{teamId}/candles")
    Candles.Page candles(@PathVariable String teamId, @RequestParam String unit,
                         @RequestParam(required = false) Long before, @RequestParam(defaultValue = "200") int limit) {
        CandleUnit u = CandleUnit.of(unit).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "없는 봉 단위예요"));
        return trading.candles(teamId, u, before == null ? null : Instant.ofEpochMilli(before), Math.max(1, Math.min(limit, 500)));
    }
}
