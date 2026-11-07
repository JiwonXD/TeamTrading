package com.fanchart.api;

import com.fanchart.feed.FeedTeams;
import com.fanchart.feed.MatchFeedSync;
import com.fanchart.trading.TradingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 경기 데이터 수집 운영(SecurityConfig의 /api/admin/**): 대회별 수집 상태, 구단과 제공처 팀 번호 잇기, 지금 바로 받기 */
@RestController
class MatchFeedController {

    record FeedLink(@NotBlank @Pattern(regexp = "api-baseball|api-football|football-data|pandascore|espn") String source,
                    @NotBlank @Size(max = 40) String externalId) {
    }

    /** 대회마다 제공처와 수집 여부(제공처 키가 있고 켜져 있는지) */
    record FeedCompetition(String competition, boolean cup, String source, String leagueId, boolean active) {
    }

    private final FeedTeams teams;
    private final MatchFeedSync sync;

    MatchFeedController(FeedTeams teams, MatchFeedSync sync) {
        this.teams = teams;
        this.sync = sync;
    }

    @PutMapping("/api/admin/teams/{teamId}/feed")
    FeedLink link(@PathVariable String teamId, @Valid @RequestBody FeedLink req) {
        teams.link(teamId, req.source(), req.externalId().trim());
        return new FeedLink(req.source(), req.externalId().trim());
    }

    @GetMapping("/api/admin/match-feed")
    List<FeedCompetition> competitions() {
        return sync.competitions().stream().map(f -> new FeedCompetition(f.competition().name(),
                f.competition().cup(), f.source(), f.leagueId(), sync.active(f))).toList();
    }

    /** 구단의 제공처별 팀 번호 */
    @GetMapping("/api/admin/teams/{teamId}/feed")
    Map<String, String> links(@PathVariable String teamId) {
        return teams.linksOf(teamId);
    }

    @PostMapping("/api/admin/match-feed/sync")
    List<MatchFeedSync.Summary> syncNow() {
        if (!sync.enabled())
            throw new TradingException("경기 데이터 키가 하나도 없어요(API_SPORTS_KEY, FOOTBALL_DATA_KEY, PANDASCORE_KEY)");
        return sync.syncSeason();
    }
}
