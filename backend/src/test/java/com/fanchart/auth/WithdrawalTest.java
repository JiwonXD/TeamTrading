package com.fanchart.auth;

import com.jayway.jsonpath.JsonPath;
import com.fanchart.TestClock;
import com.fanchart.account.AccountStore;
import com.fanchart.engine.OrderType;
import com.fanchart.engine.Side;
import com.fanchart.ledger.Reconciliation;
import com.fanchart.trading.TradingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
class WithdrawalTest {

    @Autowired MockMvc mvc;
    @Autowired TradingService trading;
    @Autowired AccountStore accounts;
    @Autowired Reconciliation reconciliation;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestClock clock;
    @Autowired Withdrawal withdrawal;

    /** MySQL에서는 여러 테스트가 한 DB를 쓰므로 테스트마다 다른 이름으로 들어간다 */
    private final String name = "bye-" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void setUp() {
        clock.set(clock.instant().plus(Duration.ofHours(12)));
    }

    private MockHttpSession devLogin(String nickname) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/dev-login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"" + nickname + "\"}"))
                .andExpect(status().isNoContent());
        return session;
    }

    private String accountId(MockHttpSession session) throws Exception {
        String body = mvc.perform(get("/api/accounts/me").session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    @Test
    void withdrawingCancelsOrdersUnlinksTheLoginAndKeepsTheBooksBalanced() throws Exception {
        MockHttpSession phone = devLogin(name);
        String id = accountId(phone);
        trading.grantShares(id, "lg", 5, 18_450);
        trading.place(id, "w-buy", "lg", Side.BUY, OrderType.LIMIT, 18_000, 3);
        trading.place(id, "w-sell", "lg", Side.SELL, OrderType.LIMIT, 19_000, 2);
        // 같은 계정으로 다른 기기에서도 로그인해 둔다
        MockHttpSession laptop = devLogin(name);
        long shares = accounts.totalShares(id, "lg");
        long points = accounts.find(id).orElseThrow().availablePoints() + accounts.find(id).orElseThrow().lockedPoints();

        mvc.perform(delete("/api/accounts/me").session(phone).with(csrf())).andExpect(status().isNoContent());

        assertThat(trading.openOrders(id)).isEmpty();
        var a = accounts.find(id).orElseThrow();
        assertThat(a.withdrawn()).isTrue();
        assertThat(a.nickname()).matches("탈퇴회원\\d{6}");
        assertThat(a.lockedPoints()).isZero();
        // 남은 포인트와 주식은 소각하지 않는다. 장부 합계가 그대로다
        assertThat(a.availablePoints()).isEqualTo(points);
        assertThat(accounts.totalShares(id, "lg")).isEqualTo(shares);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_identities WHERE account_id = ?", Integer.class, id))
                .isZero();
        assertThat(reconciliation.run().balanced()).isTrue();

        // 두 기기 모두 로그아웃된다
        mvc.perform(get("/api/accounts/me").session(phone)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/accounts/me").session(laptop)).andExpect(status().isUnauthorized());
        assertThat(laptop.isInvalid()).isTrue();

        // 순위에도 나오지 않는다
        mvc.perform(get("/api/rankings")).andExpect(jsonPath("$[?(@.nickname == '" + a.nickname() + "')]").isEmpty());
    }

    @Test
    void theSameLoginCannotSignUpAgainForThirtyDays() throws Exception {
        MockHttpSession session = devLogin(name);
        String first = accountId(session);
        mvc.perform(delete("/api/accounts/me").session(session).with(csrf())).andExpect(status().isNoContent());

        clock.advance(Withdrawal.REJOIN_BLOCK.minusSeconds(1));
        mvc.perform(post("/api/auth/dev-login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"" + name + "\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value("탈퇴하고 30일이 지나야 다시 가입할 수 있어요"));

        clock.advance(Duration.ofSeconds(1));
        String second = accountId(devLogin(name));
        assertThat(second).isNotEqualTo(first);
        assertThat(accounts.find(second).orElseThrow().availablePoints()).isEqualTo(TradingService.START_POINTS);
        // 남겨 둔 것은 식별 번호가 아니라 해시다
        assertThat(jdbc.queryForList("SELECT identity_hash FROM withdrawn_identities", String.class))
                .allMatch(h -> h.matches("[0-9a-f]{64}")).doesNotContain(name);
    }

    @Test
    void withdrawingTwiceChangesNothingTheSecondTime() throws Exception {
        String id = accountId(devLogin(name));
        withdrawal.withdraw(id);
        String nickname = accounts.find(id).orElseThrow().nickname();

        withdrawal.withdraw(id);

        assertThat(accounts.find(id).orElseThrow().nickname()).isEqualTo(nickname);
    }
}
