package com.fanchart.auth;

import com.fanchart.TestClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 접속 IP마다 아이디 가입·로그인 횟수를 제한한다 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClock.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AuthRateLimitTest {

    @Autowired MockMvc mvc;
    @Autowired TestClock clock;

    private ResultActions postFrom(String ip, String path, String json) throws Exception {
        return mvc.perform(post(path).with(csrf()).with(r -> {
            r.setRemoteAddr(ip);
            return r;
        }).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions signUpFrom(String ip) throws Exception {
        String id = "r" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return postFrom(ip, "/api/auth/signup", "{\"loginId\":\"" + id + "\",\"password\":\"secret123\"}");
    }

    private ResultActions loginFrom(String ip) throws Exception {
        return postFrom(ip, "/api/auth/login", "{\"loginId\":\"nobody1\",\"password\":\"secret123\"}");
    }

    @Test
    void signupIsLimitedPerIpForAnHour() throws Exception {
        for (int i = 0; i < AuthRateLimit.Action.SIGNUP.max; i++) signUpFrom("203.0.113.1").andExpect(status().isNoContent());

        signUpFrom("203.0.113.1").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value("가입 요청이 너무 많아요. 1시간 뒤에 다시 시도해 주세요"));
        // 다른 IP는 따로 센다
        signUpFrom("203.0.113.2").andExpect(status().isNoContent());

        clock.advance(Duration.ofHours(1));
        signUpFrom("203.0.113.1").andExpect(status().isNoContent());
    }

    @Test
    void loginAttemptsAreLimitedPerIpAcrossAccounts() throws Exception {
        for (int i = 0; i < AuthRateLimit.Action.LOGIN.max; i++)
            loginFrom("203.0.113.1").andExpect(jsonPath("$.message").value(PasswordLogin.WRONG));

        loginFrom("203.0.113.1").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value("로그인 시도가 너무 많아요. 10분 뒤에 다시 시도해 주세요"));
        loginFrom("203.0.113.2").andExpect(jsonPath("$.message").value(PasswordLogin.WRONG));

        clock.advance(Duration.ofMinutes(10));
        loginFrom("203.0.113.1").andExpect(jsonPath("$.message").value(PasswordLogin.WRONG));
    }
}
