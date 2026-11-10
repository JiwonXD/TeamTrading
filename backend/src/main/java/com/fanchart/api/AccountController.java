package com.fanchart.api;

import com.fanchart.account.Account;
import com.fanchart.account.LeagueTabs;
import com.fanchart.account.NicknameService;
import com.fanchart.account.Watchlist;
import com.fanchart.api.ApiDtos.AccountResponse;
import com.fanchart.api.ApiDtos.LeagueTabsRequest;
import com.fanchart.api.ApiDtos.MyTradeResponse;
import com.fanchart.api.ApiDtos.NicknameRequest;
import com.fanchart.api.ApiDtos.OrderResponse;
import com.fanchart.api.ApiDtos.RankingEntry;
import com.fanchart.auth.Withdrawal;
import com.fanchart.trading.TradingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 계정은 로그인(소셜 또는 개발용)으로만 생긴다. 내 계정은 세션의 로그인 정보로 구분한다 */
@RestController
class AccountController {

    private final TradingService trading;
    private final NicknameService nicknames;
    private final Withdrawal withdrawal;
    private final LeagueTabs leagueTabs;
    private final Watchlist watchlist;

    AccountController(TradingService trading, NicknameService nicknames, Withdrawal withdrawal, LeagueTabs leagueTabs,
                      Watchlist watchlist) {
        this.trading = trading;
        this.nicknames = nicknames;
        this.withdrawal = withdrawal;
        this.leagueTabs = leagueTabs;
        this.watchlist = watchlist;
    }

    /** 탈퇴. 대기 주문을 취소하고 로그인 연결을 끊은 뒤 이 세션도 끝낸다 */
    @DeleteMapping("/api/accounts/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void withdraw(@AuthenticationPrincipal String accountId, HttpServletRequest request) {
        withdrawal.withdraw(accountId);
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
    }

    @GetMapping("/api/accounts/me")
    AccountResponse me(@AuthenticationPrincipal String accountId) {
        return view(trading.account(accountId));
    }

    @PutMapping("/api/accounts/me/nickname")
    AccountResponse changeNickname(@AuthenticationPrincipal String accountId, @Valid @RequestBody NicknameRequest req) {
        return view(nicknames.change(accountId, req.nickname()));
    }

    @GetMapping("/api/accounts/me/league-tabs")
    LeagueTabs.Prefs leagueTabs(@AuthenticationPrincipal String accountId) {
        return leagueTabs.get(accountId);
    }

    @PutMapping("/api/accounts/me/league-tabs")
    LeagueTabs.Prefs saveLeagueTabs(@AuthenticationPrincipal String accountId, @Valid @RequestBody LeagueTabsRequest req) {
        return leagueTabs.save(accountId, req.order(), req.hidden());
    }

    @GetMapping("/api/accounts/me/watchlist")
    Watchlist.Entries watchlist(@AuthenticationPrincipal String accountId) {
        return watchlist.get(accountId);
    }

    @PutMapping("/api/accounts/me/watchlist/{teamId}")
    Watchlist.Entries watch(@AuthenticationPrincipal String accountId, @PathVariable String teamId) {
        return watchlist.add(accountId, teamId);
    }

    @DeleteMapping("/api/accounts/me/watchlist/{teamId}")
    Watchlist.Entries unwatch(@AuthenticationPrincipal String accountId, @PathVariable String teamId) {
        return watchlist.remove(accountId, teamId);
    }

    @GetMapping("/api/accounts/me/orders")
    List<OrderResponse> myOrders(@AuthenticationPrincipal String accountId) {
        trading.account(accountId);
        return trading.openOrders(accountId).stream().map(OrderResponse::of).toList();
    }

    @GetMapping("/api/accounts/me/trades")
    List<MyTradeResponse> myTrades(@AuthenticationPrincipal String accountId,
                                   @RequestParam(defaultValue = "20") int limit) {
        trading.account(accountId);
        return trading.myTrades(accountId, Math.min(Math.max(limit, 1), 100)).stream()
                .map(t -> MyTradeResponse.of(t, accountId)).toList();
    }

    @GetMapping("/api/rankings")
    List<RankingEntry> rankings(@AuthenticationPrincipal String accountId) {
        record Row(Account account, long value, double rate) {
        }
        List<Row> rows = new ArrayList<>();
        for (Account a : trading.allAccounts()) {
            // 탈퇴한 계정과 운영자(증자 물량을 든 발행자)는 순위에 넣지 않는다
            if (a.withdrawn() || trading.isIssuer(a.id())) continue;
            long value = trading.totalValue(a);
            rows.add(new Row(a, value, (double) value / a.initialValue() - 1));
        }
        rows.sort(Comparator.comparingDouble(Row::rate).reversed());
        List<RankingEntry> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            out.add(new RankingEntry(i + 1, r.account().nickname(), r.value(), r.rate(),
                    r.account().id().equals(accountId)));
        }
        return out;
    }

    private AccountResponse view(Account a) {
        long value = trading.totalValue(a);
        return new AccountResponse(a.id(), a.nickname(), a.availablePoints(), a.lockedPoints(),
                value, (double) value / a.initialValue() - 1, trading.holdings(a.id()), nicknames.changeableAt(a), isAdmin());
    }

    /** 지금 요청한 계정이 운영자인지. 화면이 운영자 전용 메뉴(시장감시)를 보일지 정한다. 권한 검사는 SecurityConfig가 한다 */
    private static boolean isAdmin() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(g -> "ROLE_ADMIN".equals(g.getAuthority()));
    }
}
