package com.teamtrading.api;

import com.teamtrading.account.Account;
import com.teamtrading.api.ApiDtos.AccountResponse;
import com.teamtrading.api.ApiDtos.OpenAccountRequest;
import com.teamtrading.api.ApiDtos.OrderResponse;
import com.teamtrading.api.ApiDtos.RankingEntry;
import com.teamtrading.trading.TradingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 로그인은 다음 단계. 지금은 X-Account-Id 헤더로 계정을 구분한다 */
@RestController
class AccountController {

    static final String ACCOUNT_HEADER = "X-Account-Id";

    private final TradingService trading;

    AccountController(TradingService trading) {
        this.trading = trading;
    }

    @PostMapping("/api/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    AccountResponse open(@Valid @RequestBody OpenAccountRequest req) {
        return view(trading.openAccount(req.nickname()));
    }

    @GetMapping("/api/accounts/me")
    AccountResponse me(@RequestHeader(ACCOUNT_HEADER) String accountId) {
        return view(trading.account(accountId));
    }

    @GetMapping("/api/accounts/me/orders")
    List<OrderResponse> myOrders(@RequestHeader(ACCOUNT_HEADER) String accountId) {
        trading.account(accountId);
        return trading.openOrders(accountId).stream().map(OrderResponse::of).toList();
    }

    @GetMapping("/api/rankings")
    List<RankingEntry> rankings() {
        record Row(Account account, long value, double rate) {
        }
        List<Row> rows = new ArrayList<>();
        for (Account a : trading.allAccounts()) {
            long value = trading.totalValue(a);
            rows.add(new Row(a, value, (double) value / a.initialPoints() - 1));
        }
        rows.sort(Comparator.comparingDouble(Row::rate).reversed());
        List<RankingEntry> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            out.add(new RankingEntry(i + 1, r.account().nickname(), r.value(), r.rate()));
        }
        return out;
    }

    private AccountResponse view(Account a) {
        long value = trading.totalValue(a);
        return new AccountResponse(a.id(), a.nickname(), a.availablePoints(), a.lockedPoints(),
                value, (double) value / a.initialPoints() - 1, trading.holdings(a.id()));
    }
}
