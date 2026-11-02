package com.teamtrading.api;

import com.teamtrading.feed.FeedTeams;
import com.teamtrading.feed.MatchFeedSync;
import com.teamtrading.trading.TradingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 경기 데이터 수집 운영(SecurityConfig의 /api/admin/**): 구단과 제공처 팀 번호 잇기, 지금 바로 받기 */
@RestController
class MatchFeedController {

    record FeedLink(@NotBlank @Pattern(regexp = "api-baseball|api-football") String source,
                    @NotBlank @Size(max = 40) String externalId) {
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

    @PostMapping("/api/admin/match-feed/sync")
    List<MatchFeedSync.Summary> syncNow() {
        if (!sync.enabled()) throw new TradingException("경기 데이터 키(MATCH_FEED_API_KEY)가 없어요");
        return sync.syncSeason();
    }
}
