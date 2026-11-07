package com.fanchart.api;

import com.fanchart.match.SeasonAward;
import com.fanchart.match.SeasonAwardService;
import com.fanchart.team.Competition;
import com.fanchart.trading.TradingException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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

/** 대회 성적 수당. 목록은 누구나, 등록·지급·취소는 운영자. 운영자 계정 id는 공개 응답에 넣지 않는다 */
@RestController
class AwardController {

    record AwardResponse(long id, String league, String competition, String season, String teamId, SeasonAward.Award award, long dps,
                         Instant recordAt, SeasonAward.Status status, String sourceUrl, Instant createdAt,
                         Instant decidedAt, Long shares, Integer holders, Long total) {
        static AwardResponse of(SeasonAward a) {
            return new AwardResponse(a.id(), a.league().name(), a.competition().name(), a.season(), a.teamId(), a.award(), a.dps(),
                    a.recordAt(), a.status(), a.sourceUrl(), a.createdAt(), a.decidedAt(), a.shares(), a.holders(),
                    a.total());
        }
    }

    /** competition을 비우면 구단 소속 리그의 정규 시즌 */
    record RegisterRequest(Competition competition, @NotNull String season, @NotNull String teamId, @NotNull SeasonAward.Award award,
                           @NotNull Instant recordAt, @NotNull String sourceUrl) {
    }

    private final SeasonAwardService awards;

    AwardController(SeasonAwardService awards) {
        this.awards = awards;
    }

    @GetMapping("/api/awards")
    List<AwardResponse> latest(@RequestParam(defaultValue = "20") int limit) {
        return awards.latest(Math.min(Math.max(limit, 1), 100)).stream().map(AwardResponse::of).toList();
    }

    @PostMapping("/api/admin/awards")
    @ResponseStatus(HttpStatus.CREATED)
    AwardResponse register(@AuthenticationPrincipal String adminId, @Valid @RequestBody RegisterRequest req) {
        return AwardResponse.of(awards.register(adminId, req.competition(), req.season(), req.teamId(), req.award(), req.recordAt(),
                req.sourceUrl()));
    }

    /** 확정 대기 없이 지금 지급한다 */
    @PostMapping("/api/admin/awards/{id}/pay")
    AwardResponse pay(@AuthenticationPrincipal String adminId, @PathVariable long id) {
        var paid = awards.pay(id, adminId);
        if (paid == null) throw new TradingException("이미 지급했거나 취소한 수당이에요");
        return AwardResponse.of(paid);
    }

    @PostMapping("/api/admin/awards/{id}/cancel")
    AwardResponse cancel(@AuthenticationPrincipal String adminId, @PathVariable long id) {
        return AwardResponse.of(awards.cancel(id, adminId));
    }
}
