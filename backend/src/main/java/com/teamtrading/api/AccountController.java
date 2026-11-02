package com.teamtrading.api;

import com.teamtrading.account.Account;
import com.teamtrading.api.ApiDtos.AccountResponse;
import com.teamtrading.api.ApiDtos.MyTradeResponse;
import com.teamtrading.api.ApiDtos.OrderResponse;
import com.teamtrading.api.ApiDtos.RankingEntry;
import com.teamtrading.trading.TradingService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 계정은 로그인(소셜 또는 개발용)으로만 생긴다. 내 계정은 세션의 로그인 정보로 구분한다 */
@RestController
class AccountController {

    private final TradingService trading;

    AccountController(TradingService trading) {
        this.trading = trading;
    }

    @GetMapping("/api/accounts/me")
    AccountResponse me(@AuthenticationPrincipal String accountId) {
        return view(trading.account(accountId));
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
        return trading.myTrades(accountId, Math.min(limit, 100)).stream()
                .map(t -> MyTradeResponse.of(t, accountId)).toList();
    }

    @GetMapping("/api/rankings")
    List<RankingEntry> rankings(@AuthenticationPrincipal String accountId) {
        record Row(Account account, long value, double rate) {
        }
        List<Row> rows = new ArrayList<>();
        for (Account a : trading.allAccounts()) {
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
                value, (double) value / a.initialValue() - 1, trading.holdings(a.id()));
    }
}
