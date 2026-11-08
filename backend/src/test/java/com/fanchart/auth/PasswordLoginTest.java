package com.fanchart.auth;

import com.fanchart.TestClock;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClock.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PasswordLoginTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestClock clock;
    @Autowired PasswordLogin passwords;
    @MockitoBean ResetMailer mailer;

    /** MySQL에서는 여러 테스트가 한 DB를 쓰므로 테스트마다 다른 아이디로 가입한다 */
    private final String id = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    private static final String PW = "secret123";

    @BeforeEach
    void setUp() {
        clock.set(clock.instant().plus(Duration.ofHours(12)));
        when(mailer.available()).thenReturn(true);
    }

    private ResultActions postJson(String path, String json, MockHttpSession session) throws Exception {
        return mvc.perform(post(path).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private MockHttpSession signUp(String loginId, String password, String email) throws Exception {
        MockHttpSession session = new MockHttpSession();
        postJson("/api/auth/signup", """
                {"loginId":"%s","password":"%s","email":%s}""".formatted(loginId, password,
                email == null ? "null" : "\"" + email + "\""), session).andExpect(status().isNoContent());
        return session;
    }

    private ResultActions login(String loginId, String password, MockHttpSession session) throws Exception {
        return postJson("/api/auth/login", """
                {"loginId":"%s","password":"%s"}""".formatted(loginId, password), session);
    }

    private String accountId(MockHttpSession session) throws Exception {
        return JsonPath.read(mvc.perform(get("/api/accounts/me").session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void signingUpLogsInWithSignupPointsAndThePasswordLogsInAgain() throws Exception {
        MockHttpSession joined = signUp(id, PW, null);
        mvc.perform(get("/api/accounts/me").session(joined))
                .andExpect(jsonPath("$.availablePoints").value(1_000_000))
                .andExpect(jsonPath("$.nickname").value(org.hamcrest.Matchers.startsWith("투자자")));

        // 아이디는 대소문자를 가리지 않는다
        MockHttpSession again = new MockHttpSession();
        login(id.toUpperCase(), PW, again).andExpect(status().isNoContent());
        assertThat(accountId(again)).isEqualTo(accountId(joined));
        // 비밀번호 원문은 어디에도 남지 않는다
        String hash = jdbc.queryForObject("SELECT password_hash FROM password_logins WHERE login_id = ?", String.class, id);
        assertThat(hash).startsWith("$2").doesNotContain(PW);
    }

    @Test
    void takenAndMalformedLoginIdsAreRejected() throws Exception {
        mvc.perform(get("/api/auth/login-ids/check").param("loginId", id)).andExpect(jsonPath("$.available").value(true));
        signUp(id, PW, null);
        mvc.perform(get("/api/auth/login-ids/check").param("loginId", id.toUpperCase()))
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.message").value("이미 쓰고 있는 아이디예요"));
        postJson("/api/auth/signup", "{\"loginId\":\"" + id + "\",\"password\":\"other456x\"}", new MockHttpSession())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value("이미 쓰고 있는 아이디예요"));
        for (String bad : List.of("ab", "1abc", "한글아이디", "abc_def", "a".repeat(21))) {
            mvc.perform(get("/api/auth/login-ids/check").param("loginId", bad)).andExpect(jsonPath("$.available").value(false));
        }
    }

    @Test
    void passwordRules() {
        assertThat(PasswordLogin.passwordProblem("short1", "abcd")).contains("비밀번호는 8~64자로 정해 주세요");
        assertThat(PasswordLogin.passwordProblem("onlyletters", "abcd")).contains("비밀번호에 영문과 숫자를 모두 넣어 주세요");
        assertThat(PasswordLogin.passwordProblem("12345678", "abcd")).contains("비밀번호에 영문과 숫자를 모두 넣어 주세요");
        assertThat(PasswordLogin.passwordProblem("비밀번호1234", "abcd")).contains("비밀번호에는 영문·숫자·특수문자만 쓸 수 있어요");
        assertThat(PasswordLogin.passwordProblem("has space1", "abcd")).contains("비밀번호에는 영문·숫자·특수문자만 쓸 수 있어요");
        assertThat(PasswordLogin.passwordProblem("myid1234x", "myid1234")).contains("비밀번호에 아이디를 넣을 수 없어요");
        assertThat(PasswordLogin.passwordProblem("Good!pass9", "abcd")).isEmpty();
    }

    @Test
    void wrongPasswordAndUnknownIdGetTheSameAnswer() throws Exception {
        signUp(id, PW, null);
        login(id, "wrong1234", new MockHttpSession()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(PasswordLogin.WRONG));
        login("nobody" + id, PW, new MockHttpSession()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(PasswordLogin.WRONG));
    }

    @Test
    void fiveWrongPasswordsLockTheIdForFifteenMinutes() throws Exception {
        signUp(id, PW, null);
        for (int i = 1; i < PasswordLogin.MAX_FAILURES; i++)
            login(id, "wrong1234", new MockHttpSession()).andExpect(jsonPath("$.message").value(PasswordLogin.WRONG));
        login(id, "wrong1234", new MockHttpSession())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("까지 로그인할 수 없어요")));
        // 잠긴 동안은 맞는 비밀번호도 받지 않는다
        login(id, PW, new MockHttpSession()).andExpect(status().isUnprocessableContent());

        clock.advance(PasswordLogin.LOCK);
        login(id, PW, new MockHttpSession()).andExpect(status().isNoContent());
    }

    @Test
    void aSuccessfulLoginResetsTheFailureCount() throws Exception {
        signUp(id, PW, null);
        for (int round = 0; round < 3; round++) {
            for (int i = 1; i < PasswordLogin.MAX_FAILURES; i++) login(id, "wrong1234", new MockHttpSession());
            login(id, PW, new MockHttpSession()).andExpect(status().isNoContent());
        }
    }

    @Test
    void concurrentGuessesCannotGetPastTheLimit() throws Exception {
        signUp(id, PW, null);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            results.add(pool.submit(() -> {
                try {
                    passwords.authenticate(id, "wrong1234");
                    return "ok";
                } catch (com.fanchart.trading.TradingException e) {
                    return e.getMessage();
                }
            }));
        }
        int wrong = 0;
        for (Future<String> f : results) if (f.get().equals(PasswordLogin.WRONG)) wrong++;
        pool.shutdown();
        // 시도를 먼저 세므로 동시에 40번 넣어도 실제로 맞춰 본 것은 잠기기 전 5번까지다
        assertThat(wrong).isLessThan(PasswordLogin.MAX_FAILURES);
        login(id, PW, new MockHttpSession()).andExpect(status().isUnprocessableContent());
    }

    @Test
    void resetLinkGoesOnlyToTheRegisteredEmailAndWorksOnce() throws Exception {
        signUp(id, PW, "me@example.com");
        postJson("/api/auth/password-reset", "{\"loginId\":\"" + id + "\",\"email\":\"other@example.com\"}", new MockHttpSession())
                .andExpect(status().isNoContent());
        verify(mailer, never()).sendReset(anyString(), anyString(), anyString());

        postJson("/api/auth/password-reset", "{\"loginId\":\"" + id + "\",\"email\":\"ME@example.com\"}", new MockHttpSession())
                .andExpect(status().isNoContent());
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(mailer).sendReset(eq("me@example.com"), eq(id), token.capture());
        // 토큰 원문은 저장하지 않는다
        assertThat(jdbc.queryForList("SELECT token_hash FROM password_reset_tokens WHERE login_id = ?", String.class, id))
                .containsExactly(Withdrawal.sha256(token.getValue()));

        // 규칙에 맞지 않는 비밀번호는 거절하고, 링크는 다시 쓸 수 있다
        postJson("/api/auth/password-reset/confirm", "{\"token\":\"" + token.getValue() + "\",\"password\":\"short\"}",
                new MockHttpSession()).andExpect(status().isUnprocessableContent());
        postJson("/api/auth/password-reset/confirm", "{\"token\":\"" + token.getValue() + "\",\"password\":\"newpass789\"}",
                new MockHttpSession()).andExpect(status().isNoContent());
        login(id, PW, new MockHttpSession()).andExpect(status().isUnprocessableContent());
        login(id, "newpass789", new MockHttpSession()).andExpect(status().isNoContent());

        postJson("/api/auth/password-reset/confirm", "{\"token\":\"" + token.getValue() + "\",\"password\":\"again789x\"}",
                new MockHttpSession()).andExpect(status().isUnprocessableContent());
    }

    @Test
    void resetLinksExpireAndCannotBeRequestedBackToBack() throws Exception {
        signUp(id, PW, "me@example.com");
        String body = "{\"loginId\":\"" + id + "\",\"email\":\"me@example.com\"}";
        postJson("/api/auth/password-reset", body, new MockHttpSession()).andExpect(status().isNoContent());
        postJson("/api/auth/password-reset", body, new MockHttpSession()).andExpect(status().isNoContent());
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(mailer, times(1)).sendReset(anyString(), anyString(), token.capture());

        clock.advance(PasswordLogin.RESET_TTL);
        postJson("/api/auth/password-reset/confirm", "{\"token\":\"" + token.getValue() + "\",\"password\":\"newpass789\"}",
                new MockHttpSession()).andExpect(status().isUnprocessableContent());
    }

    @Test
    void withoutMailSettingsThereIsNoPasswordReset() throws Exception {
        when(mailer.available()).thenReturn(false);
        signUp(id, PW, "me@example.com");
        mvc.perform(get("/api/auth/options")).andExpect(jsonPath("$.passwordReset").value(false));
        postJson("/api/auth/password-reset", "{\"loginId\":\"" + id + "\",\"email\":\"me@example.com\"}", new MockHttpSession())
                .andExpect(status().isUnprocessableContent());
        verify(mailer, never()).sendReset(anyString(), anyString(), any());
    }

    @Test
    void withdrawingDeletesTheCredentialsAndBlocksTheSameIdForThirtyDays() throws Exception {
        MockHttpSession session = signUp(id, PW, "me@example.com");
        mvc.perform(delete("/api/accounts/me").session(session).with(csrf())).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_logins WHERE login_id = ?", Integer.class, id)).isZero();
        login(id, PW, new MockHttpSession()).andExpect(jsonPath("$.message").value(PasswordLogin.WRONG));
        postJson("/api/auth/signup", "{\"loginId\":\"" + id + "\",\"password\":\"" + PW + "\"}", new MockHttpSession())
                .andExpect(jsonPath("$.message").value("탈퇴하고 30일이 지나야 다시 가입할 수 있어요"));

        clock.advance(Withdrawal.REJOIN_BLOCK);
        signUp(id, PW, null);
    }
}
