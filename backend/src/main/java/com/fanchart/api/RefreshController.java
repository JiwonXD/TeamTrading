package com.fanchart.api;

import com.fanchart.api.AdController.StatusResponse;
import com.fanchart.api.ApiDtos.AccountResponse;
import com.fanchart.api.ApiDtos.AlertResponse;
import com.fanchart.api.ApiDtos.MyTradeResponse;
import com.fanchart.api.ApiDtos.OrderResponse;
import com.fanchart.api.ApiDtos.QuoteResponse;
import com.fanchart.api.ApiDtos.RankingEntry;
import com.fanchart.api.MatchController.MatchResponse;
import com.fanchart.api.OfferingController.OfferingResponse;
import com.fanchart.api.OfferingController.SubscriptionResponse;
import com.fanchart.api.PostController.PostResponse;
import com.fanchart.match.MyDividend;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 화면의 3초 새로 고침을 요청 한 번으로 돌려준다(docs/troubleshooting.md 49).
 * 서버 CPU의 대부분이 조회 내용이 아니라 요청 한 건마다 드는 보안 필터·지표·응답 처리에 쓰여서, 같은 내용을 11번 나눠 받지 않는다.
 * 각 부분은 따로 있는 조회 API와 같은 값이다.
 */
@RestController
class RefreshController {

    /** 화면은 보고 있는 구단 토론만 받는다. 한 번에 받을 수 있는 구단 수의 상한 */
    static final int MAX_POST_TEAMS = 10;

    record Mine(AccountResponse account, List<OrderResponse> orders, List<MyTradeResponse> trades,
                List<MyDividend> dividends, List<SubscriptionResponse> subscriptions, StatusResponse ads,
                List<AlertResponse> alerts) {
    }

    /** @param mine 로그인한 계정의 값. 요청에서 mine=false면 null */
    record RefreshResponse(List<QuoteResponse> teams, List<RankingEntry> rankings, List<MatchResponse> matches,
                           List<OfferingResponse> offerings, Mine mine, Map<String, List<PostResponse>> posts) {
    }

    private final MarketController market;
    private final AccountController accounts;
    private final MatchController matches;
    private final OfferingController offerings;
    private final AdController ads;
    private final PostController posts;
    private final SurveillanceController surveillance;

    RefreshController(MarketController market, AccountController accounts, MatchController matches,
                      OfferingController offerings, AdController ads, PostController posts,
                      SurveillanceController surveillance) {
        this.market = market;
        this.accounts = accounts;
        this.matches = matches;
        this.offerings = offerings;
        this.ads = ads;
        this.posts = posts;
        this.surveillance = surveillance;
    }

    /**
     * @param mine  true면 내 계정 값도 받는다. 로그인하지 않았으면 401이다(따로 부르던 /api/accounts/me와 같다)
     * @param posts 토론 글(최근 30개)을 받을 구단들
     */
    @GetMapping("/api/refresh")
    RefreshResponse refresh(@AuthenticationPrincipal String accountId,
                            @RequestParam(defaultValue = "false") boolean mine,
                            @RequestParam(name = "posts", defaultValue = "") List<String> postTeams) {
        if (mine && accountId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (postTeams.size() > MAX_POST_TEAMS)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "토론은 한 번에 " + MAX_POST_TEAMS + "개 구단까지 받아요");
        Map<String, List<PostResponse>> postsByTeam = new LinkedHashMap<>();
        for (String teamId : postTeams) postsByTeam.put(teamId, posts.list(accountId, teamId, null, 30));
        return new RefreshResponse(
                market.quotes(),
                accounts.rankings(accountId),
                matches.latest(9),
                offerings.recent(10),
                mine ? mine(accountId) : null,
                postsByTeam);
    }

    private Mine mine(String accountId) {
        return new Mine(
                accounts.me(accountId),
                accounts.myOrders(accountId),
                accounts.myTrades(accountId, 50),
                matches.myDividends(accountId, 30),
                offerings.mine(accountId, 20),
                ads.status(accountId),
                isAdmin() ? surveillance.alerts(50) : List.of());
    }

    private static boolean isAdmin() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(g -> "ROLE_ADMIN".equals(g.getAuthority()));
    }
}
