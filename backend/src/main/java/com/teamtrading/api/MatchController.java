package com.teamtrading.api;

import com.teamtrading.match.Match;
import com.teamtrading.match.MatchService;
import com.teamtrading.match.DividendRow;
import com.teamtrading.match.MyDividend;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 경기 일정·결과·배당. 결과 입력과 즉시 지급은 운영자만(SecurityConfig의 /api/admin/**) */
@RestController
class MatchController {

    record MatchResponse(long id, String league, String homeTeamId, String awayTeamId, Instant scheduledAt,
                         String status, Integer homeScore, Integer awayScore, String dividendStatus,
                         List<DividendRow> dividends) {
    }

    record ScheduleRequest(@NotBlank String homeTeamId, @NotBlank String awayTeamId, @NotNull Instant scheduledAt,
                           @Size(max = 64) String sourceRef) {
    }

    record ResultRequest(@NotNull @Min(0) Integer homeScore, @NotNull @Min(0) Integer awayScore) {
    }

    private final MatchService matches;

    MatchController(MatchService matches) {
        this.matches = matches;
    }

    @GetMapping("/api/teams/{teamId}/matches")
    List<MatchResponse> teamMatches(@PathVariable String teamId, @RequestParam(defaultValue = "10") int limit) {
        return matches.recent(teamId, Math.min(limit, 50)).stream().map(this::view).toList();
    }

    @GetMapping("/api/matches")
    List<MatchResponse> latest(@RequestParam(defaultValue = "10") int limit) {
        return matches.latest(Math.min(limit, 50)).stream().map(this::view).toList();
    }

    @GetMapping("/api/matches/{matchId}")
    MatchResponse match(@PathVariable long matchId) {
        return view(matches.match(matchId));
    }

    @GetMapping("/api/accounts/me/dividends")
    List<MyDividend> myDividends(@AuthenticationPrincipal String accountId,
                                           @RequestParam(defaultValue = "20") int limit) {
        return matches.myDividends(accountId, Math.min(limit, 100));
    }

    @PostMapping("/api/admin/matches")
    @ResponseStatus(HttpStatus.CREATED)
    MatchResponse schedule(@Valid @RequestBody ScheduleRequest req) {
        String ref = req.sourceRef() == null ? UUID.randomUUID().toString() : req.sourceRef();
        return view(matches.schedule(req.homeTeamId(), req.awayTeamId(), req.scheduledAt(), "manual", ref));
    }

    @PostMapping("/api/admin/matches/{matchId}/result")
    MatchResponse result(@PathVariable long matchId, @Valid @RequestBody ResultRequest req) {
        return view(matches.recordResult(matchId, req.homeScore(), req.awayScore()));
    }

    @PostMapping("/api/admin/matches/{matchId}/cancel")
    MatchResponse cancel(@PathVariable long matchId) {
        return view(matches.cancel(matchId));
    }

    /** 확정 대기 없이 바로 지급 */
    @PostMapping("/api/admin/matches/{matchId}/settle")
    MatchResponse settle(@PathVariable long matchId) {
        matches.settle(matchId);
        return view(matches.match(matchId));
    }

    private MatchResponse view(Match m) {
        return new MatchResponse(m.id(), m.league().name(), m.homeTeamId(), m.awayTeamId(), m.scheduledAt(),
                m.status().name(), m.homeScore(), m.awayScore(), m.dividendStatus().name(),
                matches.dividends(m.id()));
    }
}
