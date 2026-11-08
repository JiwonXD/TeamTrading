package com.fanchart.auth;

import com.fanchart.TestClock;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.fanchart.auth.TestLogin.login;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 아이디로 가입한 계정에 소셜 로그인을 붙인다. 제공자 왕복은 돌아온 뒤의 처리(OAuthLoginSuccess)를 직접 불러 확인한다 */
@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.google.client-id=test-id",
        "spring.security.oauth2.client.registration.google.client-secret=test-secret",
        "spring.security.oauth2.client.registration.google.scope=openid"})
@AutoConfigureMockMvc
@Import(TestClock.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SocialLinkTest {

    @Autowired MockMvc mvc;
    @Autowired AccountLogin accounts;
    @Autowired Withdrawal withdrawal;
    @Autowired TestClock clock;

    private final String id = "s" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    private final String sub = "g-" + UUID.randomUUID();
    private OAuthLoginSuccess handler;

    @BeforeEach
    void setUp() {
        clock.set(clock.instant().plus(Duration.ofHours(12)));
        handler = new OAuthLoginSuccess(accounts, withdrawal, clock);
    }

    private MockHttpSession signUp() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/signup").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + id + "\",\"password\":\"secret123\"}")).andExpect(status().isNoContent());
        return session;
    }

    private String accountId(MockHttpSession session) throws Exception {
        return JsonPath.read(mvc.perform(get("/api/accounts/me").session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    /** 구글에서 돌아온 요청을 흉내 낸다. 돌려보낸 주소를 돌려준다 */
    private String returnFromGoogle(MockHttpSession session, String subject) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        req.setSession(session);
        MockHttpServletResponse res = new MockHttpServletResponse();
        var user = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OIDC_USER")), Map.of("sub", subject), "sub");
        handler.onAuthenticationSuccess(req, res, new OAuth2AuthenticationToken(user, user.getAuthorities(), "google"));
        return res.getRedirectedUrl();
    }

    private void startLink(MockHttpSession session) throws Exception {
        mvc.perform(post("/api/accounts/me/logins/google").session(session).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/oauth2/authorization/google"));
    }

    @Test
    void aPasswordAccountLinksGoogleAndThenLogsInWithEither() throws Exception {
        MockHttpSession session = signUp();
        String me = accountId(session);
        mvc.perform(get("/api/accounts/me/logins").session(session))
                .andExpect(jsonPath("$.loginId").value(id))
                .andExpect(jsonPath("$.providers[0].id").value("google"))
                .andExpect(jsonPath("$.providers[0].linked").value(false));

        startLink(session);
        assertThat(returnFromGoogle(session, sub)).isEqualTo("/?link=done#portfolio");

        // 연동 뒤에도 같은 계정으로 로그인돼 있다
        assertThat(accountId(session)).isEqualTo(me);
        mvc.perform(get("/api/accounts/me/logins").session(session)).andExpect(jsonPath("$.providers[0].linked").value(true));
        // 구글로 로그인해도 같은 계정이고, 가입 보상을 다시 받지 않는다
        assertThat(returnFromGoogle(new MockHttpSession(), sub)).isEqualTo("/");
        assertThat(accounts.findOrCreate("google", sub)).isEqualTo(me);
        mvc.perform(post("/api/accounts/me/logins/google").session(session).with(csrf()))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.message").value("이미 연동했어요"));
    }

    @Test
    void aGoogleAccountAlreadyUsedByAnotherAccountIsRefused() throws Exception {
        String other = accounts.findOrCreate("google", sub);
        MockHttpSession session = signUp();
        String me = accountId(session);

        startLink(session);
        assertThat(returnFromGoogle(session, sub)).isEqualTo("/?link=taken#portfolio");

        assertThat(accountId(session)).isEqualTo(me);
        assertThat(accounts.findOrCreate("google", sub)).isEqualTo(other);
        mvc.perform(get("/api/accounts/me/logins").session(session)).andExpect(jsonPath("$.providers[0].linked").value(false));
    }

    @Test
    void onlyOneGooglePerAccount() {
        String me = accounts.findOrCreate("google", sub);
        assertThat(accounts.link("google", "g-second-" + sub, me)).isEqualTo(AccountLogin.LinkResult.PROVIDER_IN_USE);
    }

    @Test
    void socialOnlyAccountsCannotLink() throws Exception {
        String me = accounts.findOrCreate("google", sub);
        mvc.perform(post("/api/accounts/me/logins/google").with(login(me)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value("아이디로 가입한 계정만 소셜 연동을 할 수 있어요"));
        mvc.perform(post("/api/accounts/me/logins/kakao").with(login(me))).andExpect(status().isNotFound());
    }

    @Test
    void anAbandonedLinkDoesNotTurnALaterGoogleLoginIntoALink() throws Exception {
        MockHttpSession session = signUp();
        String me = accountId(session);
        startLink(session);
        clock.advance(SocialLinks.INTENT_TTL.plusSeconds(1));

        // 연동이 아니라 그 구글 계정의 새 가입·로그인으로 처리된다
        assertThat(returnFromGoogle(session, sub)).isEqualTo("/");
        assertThat(accounts.findOrCreate("google", sub)).isNotEqualTo(me);
    }
}
