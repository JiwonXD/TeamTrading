package com.teamtrading.auth;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
import java.util.List;

/**
 * 로그인한 계정. 세션에 이것만 저장한다. 소셜 로그인 제공자의 토큰이나 프로필은 세션에 남기지 않는다.
 * 컨트롤러는 {@code @AuthenticationPrincipal String accountId}로 계정 id를 받는다.
 */
public class AccountAuthentication extends AbstractAuthenticationToken {

    private final String accountId;

    public AccountAuthentication(String accountId, boolean admin) {
        super(authorities(admin));
        this.accountId = accountId;
        setAuthenticated(true);
    }

    private static List<GrantedAuthority> authorities(boolean admin) {
        List<GrantedAuthority> list = new ArrayList<>();
        list.add(new SimpleGrantedAuthority("ROLE_USER"));
        if (admin) list.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return list;
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public String getPrincipal() {
        return accountId;
    }
}
