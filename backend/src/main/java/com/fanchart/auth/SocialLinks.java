package com.fanchart.auth;

import com.fanchart.trading.NotFoundException;
import com.fanchart.trading.TradingException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.Serializable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 아이디로 가입한 계정에 구글·카카오 로그인을 더 붙인다(ADR 0037).
 * 연동 버튼을 누르면 세션에 "이 계정에 붙일 것"을 적고 제공자 로그인으로 보낸다. 돌아오면 {@link OAuthLoginSuccess}가
 * 그 표시를 보고 새 계정을 만들지 않고 지금 계정에 연결한다.
 */
@RestController
class SocialLinks {

    static final String SESSION_KEY = SocialLinks.class.getName() + ".intent";
    /** 연동 버튼을 누르고 이 시간 안에 돌아와야 연동으로 본다. 그만두고 한참 뒤 한 소셜 로그인이 연동으로 처리되지 않게 */
    static final Duration INTENT_TTL = Duration.ofMinutes(10);

    record Intent(String accountId, String provider, Instant at) implements Serializable {
    }

    record LoginMethod(String id, String name, boolean linked) {
    }

    /** @param loginId 아이디로 가입했으면 그 아이디. 소셜로만 가입한 계정은 null이고 연동을 쓸 수 없다 */
    record Logins(String loginId, List<LoginMethod> providers) {
    }

    record LinkStart(String url) {
    }

    private final IdentityStore identities;
    private final PasswordLoginStore passwords;
    private final ObjectProvider<ClientRegistrationRepository> registrations;
    private final Clock clock;

    SocialLinks(IdentityStore identities, PasswordLoginStore passwords,
                ObjectProvider<ClientRegistrationRepository> registrations, Clock clock) {
        this.identities = identities;
        this.passwords = passwords;
        this.registrations = registrations;
        this.clock = clock;
    }

    @GetMapping("/api/accounts/me/logins")
    Logins logins(@AuthenticationPrincipal String accountId) {
        List<String> mine = identities.providersOf(accountId);
        List<LoginMethod> providers = new ArrayList<>();
        for (ClientRegistration r : registrations())
            providers.add(new LoginMethod(r.getRegistrationId(), r.getClientName(), mine.contains(r.getRegistrationId())));
        return new Logins(passwords.loginIdOf(accountId).orElse(null), providers);
    }

    @PostMapping("/api/accounts/me/logins/{provider}")
    LinkStart startLink(@AuthenticationPrincipal String accountId, @PathVariable String provider, HttpServletRequest request) {
        if (registrations().stream().noneMatch(r -> r.getRegistrationId().equals(provider)))
            throw new NotFoundException("연동할 수 없는 로그인이에요");
        if (passwords.loginIdOf(accountId).isEmpty()) throw new TradingException("아이디로 가입한 계정만 소셜 연동을 할 수 있어요");
        if (identities.providersOf(accountId).contains(provider)) throw new TradingException("이미 연동했어요");
        request.getSession().setAttribute(SESSION_KEY, new Intent(accountId, provider, clock.instant()));
        return new LinkStart("/oauth2/authorization/" + provider);
    }

    private List<ClientRegistration> registrations() {
        List<ClientRegistration> list = new ArrayList<>();
        if (registrations.getIfAvailable() instanceof InMemoryClientRegistrationRepository repo) repo.forEach(list::add);
        return list;
    }
}
