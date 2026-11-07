package com.teamtrading.api;

import com.teamtrading.leaderboard.Leaderboard;
import com.teamtrading.leaderboard.Leaderboard.Period;
import com.teamtrading.leaderboard.Leaderboard.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** 기간별 수익률·손익 순위. 계정 id는 내보내지 않는다 */
@RestController
class LeaderboardController {

    record Row(int rank, String nickname, double returnRate, long pnl, int trades, boolean me) {
    }

    record Response(String period, LocalDate from, LocalDate to, List<Row> entries, Leaderboard.Mine mine) {
    }

    private final Leaderboard leaderboard;

    LeaderboardController(Leaderboard leaderboard) {
        this.leaderboard = leaderboard;
    }

    @GetMapping("/api/leaderboard")
    Response board(@AuthenticationPrincipal String accountId,
                   @RequestParam(defaultValue = "TODAY") Period period,
                   @RequestParam(defaultValue = "RETURN") Sort sort,
                   @RequestParam(defaultValue = "50") int limit) {
        Leaderboard.Board b = leaderboard.board(period, sort, accountId, Math.min(Math.max(limit, 1), 100));
        return new Response(b.period().name(), b.from(), b.to(), b.entries().stream()
                .map(e -> new Row(e.rank(), e.nickname(), e.returnRate(), e.pnl(), e.trades(),
                        e.accountId().equals(accountId)))
                .toList(), b.mine());
    }
}
