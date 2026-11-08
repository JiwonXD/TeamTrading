package com.fanchart.auth;

import com.fanchart.trading.TradingException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import java.io.IOException;
import java.time.Clock;
import java.util.Map;

/**
 * 소셜 로그인에서 돌아왔을 때. 연동 중이면(세션에 {@link SocialLinks.Intent}) 지금 계정에 붙이고 내 투자 화면으로,
 * 아니면 그 소셜 계정의 계정으로 로그인한다(처음이면 가입)
 */
class OAuthLoginSuccess implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    private final AccountLogin accounts;
    private final Withdrawal withdrawal;
    private final Clock clock;

    OAuthLoginSuccess(AccountLogin accounts, Withdrawal withdrawal, Clock clock) {
        this.accounts = accounts;
        this.withdrawal = withdrawal;
        this.clock = clock;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest req, HttpServletResponse res, Authentication auth) throws IOException {
        OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) auth;
        String provider = token.getAuthorizedClientRegistrationId();
        String subject = subject(provider, token.getPrincipal().getAttributes());
        SocialLinks.Intent intent = takeIntent(req);
        if (intent != null && intent.provider().equals(provider)) {
            // 제공자 로그인을 거치며 세션의 로그인 상태가 소셜 토큰으로 바뀌었으므로 원래 계정으로 되돌린다
            accounts.signIn(intent.accountId(), req, res);
            String result = switch (accounts.link(provider, subject, intent.accountId())) {
                case LINKED, ALREADY_MINE -> "done";
                case TAKEN -> "taken";
                case PROVIDER_IN_USE -> "exists";
            };
            res.sendRedirect("/?link=" + result + "#portfolio");
            return;
        }
        String accountId;
        try {
            accountId = accounts.findOrCreate(provider, subject);
        } catch (TradingException e) {
            res.sendRedirect("/#signup-blocked");
            return;
        }
        accounts.signIn(accountId, req, res);
        res.sendRedirect("/");
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest req, HttpServletResponse res,
                                        org.springframework.security.core.AuthenticationException e) throws IOException {
        SocialLinks.Intent intent = takeIntent(req);
        if (intent != null) {
            accounts.signIn(intent.accountId(), req, res);
            res.sendRedirect("/?link=failed#portfolio");
            return;
        }
        res.sendRedirect("/#login-failed");
    }

    /** 세션의 연동 표시를 꺼내고 지운다. 오래됐거나 그 사이 탈퇴한 계정이면 없는 것으로 본다 */
    private SocialLinks.Intent takeIntent(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) return null;
        Object value = session.getAttribute(SocialLinks.SESSION_KEY);
        session.removeAttribute(SocialLinks.SESSION_KEY);
        if (!(value instanceof SocialLinks.Intent intent)) return null;
        if (intent.at().plus(SocialLinks.INTENT_TTL).isBefore(clock.instant())) return null;
        if (withdrawal.isWithdrawn(intent.accountId())) return null;
        return intent;
    }

    /** 제공자의 사용자 고유 id */
    static String subject(String provider, Map<String, Object> attributes) {
        Object id = switch (provider) {
            case "google" -> attributes.get("sub");
            default -> attributes.get("id");
        };
        if (id == null) throw new IllegalStateException("no user id from " + provider);
        return id.toString();
    }
}
