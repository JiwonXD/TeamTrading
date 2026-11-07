package com.fanchart.team;

import com.fanchart.TestClock;
import com.fanchart.engine.OrderType;
import com.fanchart.engine.Side;
import com.fanchart.ledger.Reconciliation;
import com.fanchart.offering.Offering;
import com.fanchart.offering.OfferingService;
import com.fanchart.trading.TradingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static com.fanchart.auth.TestLogin.admin;
import static com.fanchart.auth.TestLogin.login;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 리그 확장: 운영자가 등록한 구단은 첫 상장 공모를 거쳐서만 거래를 시작한다 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClock.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class TeamRegistryTest {

    static final String BODY = """
            {"id":"kt-rolster","name":"KT 롤스터","shortName":"KT","league":"LCK","startPrice":12000,"color":"#E60012"}""";

    @Autowired MockMvc mvc;
    @Autowired TradingService trading;
    @Autowired OfferingService offerings;
    @Autowired TeamCatalog catalog;
    @Autowired Reconciliation reconciliation;
    @Autowired TestClock clock;

    private String a;
    private String b;

    @BeforeEach
    void setUp() {
        clock.set(clock.instant().plus(Duration.ofHours(12)));
        a = trading.openAccount("a").id();
        b = trading.openAccount("b").id();
    }

    private void register() throws Exception {
        mvc.perform(post("/api/admin/teams").with(admin(a)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.listed").value(false))
                .andExpect(jsonPath("$.color").value("#e60012"))
                .andExpect(jsonPath("$.basePrice").value(12_000));
    }

    private void buy(String who, long price, long qty) {
        trading.place(who, UUID.randomUUID().toString(), "kt-rolster", Side.BUY, OrderType.LIMIT, price, qty);
    }

    @Test
    void onlyAdminsRegisterValidNewTeamsAndTheyAppearUnlisted() throws Exception {
        mvc.perform(post("/api/admin/teams").with(login(a)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        register();
        mvc.perform(post("/api/admin/teams").with(admin(a)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/admin/teams").with(admin(a)).contentType(MediaType.APPLICATION_JSON)
                .content(BODY.replace("kt-rolster", "KT Rolster"))).andExpect(status().isUnprocessableContent());
        mvc.perform(post("/api/admin/teams").with(admin(a)).contentType(MediaType.APPLICATION_JSON)
                .content(BODY.replace("kt-rolster", "kt2").replace("12000", "12005"))).andExpect(status().isUnprocessableContent());

        mvc.perform(get("/api/teams")).andExpect(jsonPath("$.length()").value(19))
                .andExpect(jsonPath("$[18].id").value("kt-rolster"))
                .andExpect(jsonPath("$[18].listed").value(false))
                .andExpect(jsonPath("$[0].listed").value(true));
        assertThatThrownBy(() -> buy(a, 12_000, 1)).hasMessageContaining("상장 전");
    }

    @Test
    void aRegisteredTeamListsThroughItsFirstOfferingAndOpeningAuction() throws Exception {
        register();
        Instant closeAt = clock.instant().plus(Duration.ofHours(1));
        Instant listingAt = closeAt.plus(Duration.ofMinutes(30));
        Offering o = offerings.create("kt-rolster", null, 100, 50, clock.instant(), closeAt, listingAt);
        assertThat(o.firstListing()).isTrue();
        offerings.subscribe(o.id(), a, 30);
        offerings.subscribe(o.id(), b, 30);
        assertThatThrownBy(() -> buy(a, 12_000, 1)).hasMessageContaining("상장 전");

        clock.set(closeAt);
        offerings.allocateDue();
        // 상장 전 동시호가: b가 판매, a가 매수 지정가를 모은다
        trading.place(b, UUID.randomUUID().toString(), "kt-rolster", Side.SELL, OrderType.LIMIT, 12_500, 10);
        buy(a, 12_500, 10);
        assertThat(trading.quote("kt-rolster").listed()).isFalse();

        clock.set(listingAt);
        trading.openDueAuctions();
        var q = trading.quote("kt-rolster");
        assertThat(q.listed()).isTrue();
        assertThat(q.lastPrice()).isEqualTo(12_500);
        assertThat(reconciliation.run().balanced()).isTrue();
    }

    @Test
    void aCanceledFirstOfferingKeepsTheTeamClosedAndARestartKeepsItClosed() throws Exception {
        register();
        Offering o = offerings.create("kt-rolster", null, 100, 50, clock.instant(),
                clock.instant().plus(Duration.ofHours(1)), clock.instant().plus(Duration.ofHours(2)));
        offerings.cancel(o.id());
        assertThatThrownBy(() -> buy(a, 12_000, 1)).hasMessageContaining("상장 전");

        trading.recover();
        assertThat(catalog.find("kt-rolster")).get().extracting(Team::ipoRequired).isEqualTo(true);
        assertThatThrownBy(() -> buy(a, 12_000, 1)).hasMessageContaining("상장 전");
        // 처음부터 있던 구단은 그대로 거래한다
        assertThat(trading.quote("lg").listed()).isTrue();
    }
}
