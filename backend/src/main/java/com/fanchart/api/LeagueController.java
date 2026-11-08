package com.fanchart.api;

import com.fanchart.team.League;
import com.fanchart.team.LeagueProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 닫아 둔 리그. 화면이 리그 탭과 구단 목록에서 뺀다 */
@RestController
class LeagueController {

    record LeaguesResponse(List<String> hidden) {
    }

    private final LeagueProperties leagues;

    LeagueController(LeagueProperties leagues) {
        this.leagues = leagues;
    }

    @GetMapping("/api/leagues")
    LeaguesResponse leagues() {
        return new LeaguesResponse(leagues.hidden().stream().sorted().map(League::name).toList());
    }
}
