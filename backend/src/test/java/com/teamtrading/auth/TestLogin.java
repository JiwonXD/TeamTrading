package com.teamtrading.auth;

import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

/** MockMvc 요청을 로그인한 계정으로 보낸다. CSRF 토큰도 함께 붙인다 */
public final class TestLogin {

    private TestLogin() {
    }

    public static RequestPostProcessor login(String accountId) {
        return request -> csrf().postProcessRequest(
                authentication(new AccountAuthentication(accountId, false)).postProcessRequest(request));
    }

    public static RequestPostProcessor admin(String accountId) {
        return request -> csrf().postProcessRequest(
                authentication(new AccountAuthentication(accountId, true)).postProcessRequest(request));
    }
}
