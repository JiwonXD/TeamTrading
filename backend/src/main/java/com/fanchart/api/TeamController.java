package com.fanchart.api;

import com.fanchart.api.ApiDtos.QuoteResponse;
import com.fanchart.team.League;
import com.fanchart.team.TeamCatalog;
import com.fanchart.trading.TradingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 리그 확장: 운영자가 구단을 등록한다. 새 구단은 첫 상장 공모(/api/admin/offerings)로 주식이 생기기 전까지 거래하지 않는다
 */
@RestController
class TeamController {

    record RegisterRequest(@NotNull String id, @NotNull String name, @NotNull String shortName, @NotNull League league,
                           @NotNull Long startPrice, @NotNull String color) {
    }

    private final TeamCatalog catalog;
    private final TradingService trading;

    TeamController(TeamCatalog catalog, TradingService trading) {
        this.catalog = catalog;
        this.trading = trading;
    }

    @PostMapping("/api/admin/teams")
    @ResponseStatus(HttpStatus.CREATED)
    QuoteResponse register(@Valid @RequestBody RegisterRequest req) {
        var team = catalog.register(req.id(), req.name(), req.shortName(), req.league(), req.startPrice(), req.color());
        return QuoteResponse.of(trading.quote(team.id()));
    }
}
