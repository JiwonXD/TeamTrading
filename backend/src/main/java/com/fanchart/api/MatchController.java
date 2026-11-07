package com.fanchart.api;

import com.fanchart.match.Match;
import com.fanchart.match.MatchService;
import com.fanchart.match.DividendRow;
import com.fanchart.match.MyDividend;
import com.fanchart.team.Competition;
import com.fanchart.team.League;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 경기 일정·결과·배당. 결과 입력과 즉시 지급은 운영자만(SecurityConfig의 /api/admin/**) */
@RestController
class MatchController {

    /** 구단 id가 null인 쪽은 상장하지 않은 팀이고 이름(homeName, awayName)만 있다 */
    record MatchResponse(long id, String league, String competition, String homeTeamId, String homeName,
                         String awayTeamId, String awayName, Instant scheduledAt,
                         String status, Integer homeScore, Integer awayScore, String dividendStatus,
                         List<DividendRow> dividends) {
    }

    /** 컵대회는 한쪽을 상장하지 않은 팀(구단 id 대신 이름)으로 넣을 수 있다. competition을 비우면 리그 정규 시즌 */
    record ScheduleRequest(Competition competition, @Size(max = 20) String homeTeamId, @Size(max = 60) String homeName,
                           @Size(max = 20) String awayTeamId, @Size(max = 60) String awayName,
                           @NotNull Instant scheduledAt, @Size(max = 64) String sourceRef) {
    }

    record ResultRequest(@NotNull @Min(0) Integer homeScore, @NotNull @Min(0) Integer awayScore) {
    }

    private static final Duration MAX_SCHEDULE_SPAN = Duration.ofDays(31);

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

    /** 리그 화면의 날짜별 일정. 기간은 화면이 고른 날짜(사용자 시간대)를 시각으로 바꿔 보낸다 */
    @GetMapping("/api/matches/schedule")
    List<MatchResponse> schedule(@RequestParam League league, @RequestParam Instant from, @RequestParam Instant to) {
        if (!from.isBefore(to) || Duration.between(from, to).compareTo(MAX_SCHEDULE_SPAN) > 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "기간은 31일 이내로 보내 주세요");
        return matches.schedule(league, from, to).stream().map(this::view).toList();
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
        return view(matches.schedule(req.competition(), blankToNull(req.homeTeamId()), req.homeName(),
                blankToNull(req.awayTeamId()), req.awayName(), req.scheduledAt(), "manual", ref));
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

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private MatchResponse view(Match m) {
        return new MatchResponse(m.id(), m.league().name(), m.competition().name(), m.homeTeamId(), m.homeName(),
                m.awayTeamId(), m.awayName(), m.scheduledAt(),
                m.status().name(), m.homeScore(), m.awayScore(), m.dividendStatus().name(),
                matches.dividends(m.id()));
    }
}
