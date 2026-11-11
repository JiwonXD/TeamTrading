package com.fanchart.auth;

import com.fanchart.trading.TradingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 자동매매 봇 계정 로그인(docs/bots.md). 비밀 값(fanchart.auth.bot-token)을 아는 봇 실행기만 이름으로 로그인하고,
 * 처음 보는 이름이면 일반 가입처럼 계정을 만든다(가입 포인트 포함). 봇 계정은 랭킹·리더보드와 봇끼리의 이상거래 탐지에서 뺀다.
 * 비밀 값으로 막으므로 가입·로그인 IP 제한을 받지 않는다(봇 1,000개를 한 기기에서 띄운다).
 */
@RestController
class BotLogin {

    static final String PROVIDER = "bot";

    record BotLoginRequest(@Pattern(regexp = "[a-z0-9-]{1,40}") String name) {
    }

    private final AccountLogin login;
    private final IdentityStore identities;
    private final TradingService trading;
    private final byte[] token;

    BotLogin(AccountLogin login, IdentityStore identities, TradingService trading, AuthProperties props) {
        this.login = login;
        this.identities = identities;
        this.trading = trading;
        this.token = props.botToken().getBytes(StandardCharsets.UTF_8);
    }

    /** 다시 뜬 서버도 봇 계정을 알아보게 한다 */
    @EventListener(ApplicationReadyEvent.class)
    void loadBots() {
        identities.accountIds(PROVIDER).forEach(trading::markBot);
    }

    @PostMapping("/api/auth/bot-login")
    ResponseEntity<?> botLogin(@RequestHeader(value = "X-Bot-Token", required = false) String given,
                               @Valid @RequestBody BotLoginRequest req,
                               HttpServletRequest request, HttpServletResponse response) {
        if (token.length == 0) return ResponseEntity.notFound().build();
        if (given == null || !MessageDigest.isEqual(token, given.getBytes(StandardCharsets.UTF_8)))
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        String accountId = login.findOrCreate(PROVIDER, req.name());
        trading.markBot(accountId);
        login.signIn(accountId, request, response);
        return ResponseEntity.noContent().build();
    }
}
