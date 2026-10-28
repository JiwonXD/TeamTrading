package com.teamtrading.api;

import com.teamtrading.api.ApiDtos.OrderBookResponse;
import com.teamtrading.api.ApiDtos.QuoteResponse;
import com.teamtrading.api.ApiDtos.TradeResponse;
import com.teamtrading.trading.TradingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
}
