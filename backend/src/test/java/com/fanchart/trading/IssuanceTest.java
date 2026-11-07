package com.fanchart.trading;

import com.fanchart.TestClock;
import com.fanchart.account.AccountStore;
import com.fanchart.engine.Level;
import com.fanchart.engine.OrderType;
import com.fanchart.engine.Side;
import com.fanchart.leaderboard.Leaderboard;
import com.fanchart.leaderboard.Leaderboard.Period;
import com.fanchart.leaderboard.Leaderboard.Sort;
import com.fanchart.ledger.Reconciliation;
import com.fanchart.team.League;
import com.fanchart.team.TeamCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static com.fanchart.auth.TestLogin.login;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** 운영자 계정의 매도는 증자다(ADR 0031) */
@SpringBootTest(properties = "fanchart.auth.admins=operator-1")
@AutoConfigureMockMvc
@Import(TestClock.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class IssuanceTest {

    static final String OPERATOR = "operator-1";

    @Autowired TradingService trading;
    @Autowired TeamCatalog catalog;
    @Autowired AccountStore accounts;
    @Autowired Reconciliation reconciliation;
    @Autowired Leaderboard leaderboard;
    @Autowired TestClock clock;
    @Autowired MockMvc mvc;

    private String buyer;

    @BeforeEach
    void setUp() {
        accounts.create(OPERATOR, "운영자", clock.instant());
        buyer = trading.openAccount("buyer").id();
    }

    private OrderReceipt sell(String clientOrderId, String team, long price, long qty) {
        return trading.place(OPERATOR, clientOrderId, team, Side.SELL, OrderType.LIMIT, price, qty);
    }

    @Test
    void operatorSellIssuesTheSharesItDoesNotHave() {
        OrderReceipt r = sell("issue-lg", "lg", 18_450, 10_000);

        assertThat(r.restingQuantity()).isEqualTo(10_000);
        assertThat(trading.orderBook("lg", 5).asks()).extracting(Level::quantity).containsExactly(10_000L);
        trading.place(buyer, "b-1", "lg", Side.BUY, OrderType.LIMIT, 18_450, 3);

        assertThat(trading.holdings(buyer)).anySatisfy(h -> {
            assertThat(h.teamId()).isEqualTo("lg");
            assertThat(h.available()).isEqualTo(3);
        });
        assertThat(reconciliation.run().balanced()).isTrue();
        // 같은 번호로 다시 보내도 한 번만 발행한다
        sell("issue-lg", "lg", 18_450, 10_000);
        assertThat(accounts.availableShares(OPERATOR, "lg") + trading.orderBook("lg", 5).asks().getFirst().quantity())
                .isEqualTo(9_997);
    }

    @Test
    void cancelKeepsTheIssuedSharesAndTheNextSellIssuesOnlyTheShortfall() {
        OrderReceipt r = sell("issue-1", "lg", 18_450, 100);
        trading.cancel(OPERATOR, r.orderId());
        assertThat(accounts.availableShares(OPERATOR, "lg")).isEqualTo(100);

        sell("issue-2", "lg", 18_450, 150);

        assertThat(accounts.availableShares(OPERATOR, "lg")).isZero();
        assertThat(trading.orderBook("lg", 5).asks().getFirst().quantity()).isEqualTo(150);
        assertThat(reconciliation.run().balanced()).isTrue();
    }

    @Test
    void operatorSellOpensANewlyRegisteredTeamWithoutAnOffering() {
        catalog.register("arsenal", "아스널", "아스널", League.EPL, 10_000, "#ef0107");
        assertThatThrownBy(() -> trading.place(buyer, "b-0", "arsenal", Side.BUY, OrderType.LIMIT, 10_000, 1))
                .hasMessageContaining("상장 전");

        sell("issue-arsenal", "arsenal", 10_000, 10_000);
        trading.place(buyer, "b-1", "arsenal", Side.BUY, OrderType.LIMIT, 10_000, 5);

        assertThat(trading.quote("arsenal").lastPrice()).isEqualTo(10_000);
        // 다시 띄워도 주식이 있으니 거래가 열려 있다
        trading.recover();
        trading.place(buyer, "b-2", "arsenal", Side.BUY, OrderType.LIMIT, 10_000, 1);
        assertThat(trading.myTrades(buyer, 10)).hasSize(2);
        assertThat(reconciliation.run().balanced()).isTrue();
    }

    @Test
    void operatorSellIsLimitOnlyAndOthersStillNeedShares() {
        assertThatThrownBy(() -> trading.place(OPERATOR, "m-1", "lg", Side.SELL, OrderType.MARKET, 0, 1))
                .hasMessageContaining("지정가");
        assertThatThrownBy(() -> trading.place(buyer, "s-1", "lg", Side.SELL, OrderType.LIMIT, 18_450, 1))
                .isInstanceOf(TradingException.class);
    }

    @Test
    void buyingFromTheOperatorIsNotSurveilledAndTheOperatorIsNotRanked() throws Exception {
        sell("issue-lg", "lg", 18_450, 100);
        for (int i = 0; i < 5; i++) trading.place(buyer, "b-" + i, "lg", Side.BUY, OrderType.LIMIT, 18_450, 1);

        assertThat(trading.alerts(10)).isEmpty();
        assertThat(leaderboard.board(Period.TODAY, Sort.RETURN, OPERATOR, 50).mine()).isNull();
        assertThat(leaderboard.board(Period.TODAY, Sort.RETURN, buyer, 50).mine()).isNotNull();
        mvc.perform(get("/api/rankings").with(login(buyer)))
                .andExpect(jsonPath("$[?(@.nickname == '운영자')]").isEmpty())
                .andExpect(jsonPath("$[?(@.nickname == 'buyer')]").isNotEmpty());
    }
}
