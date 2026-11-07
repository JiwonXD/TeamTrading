package com.teamtrading.match;

import com.teamtrading.TestClock;
import com.teamtrading.account.AccountStore;
import com.teamtrading.ledger.Reconciliation;
import com.teamtrading.match.SeasonAward.Award;
import com.teamtrading.match.SeasonAward.Status;
import com.teamtrading.trading.IdempotencyConflictException;
import com.teamtrading.trading.TradingService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.teamtrading.auth.TestLogin.admin;
import static com.teamtrading.auth.TestLogin.login;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClock.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SeasonAwardTest {

    static final String URL = "https://www.koreabaseball.com/";

    @Autowired MockMvc mvc;
    @Autowired SeasonAwardService awards;
    @Autowired TradingService trading;
    @Autowired AccountStore accounts;
    @Autowired Reconciliation reconciliation;
    @Autowired TestClock clock;

    private String holder;
    private String lateBuyer;
    private Instant recordAt;

    @BeforeEach
    void setUp() {
        holder = trading.openAccount("holder").id();
        lateBuyer = trading.openAccount("late").id();
        trading.grantShares(holder, "lg", 10, 18_450);
        clock.advance(Duration.ofMinutes(1));
        recordAt = clock.instant();
        clock.advance(Duration.ofMinutes(1));
        // 기준 시점 뒤에 받은 주식은 수당 대상이 아니다
        trading.grantShares(lateBuyer, "lg", 50, 18_450);
    }

    private long points(String id) {
        return accounts.find(id).orElseThrow().availablePoints();
    }

    @Test
    void holdersAtTheRecordTimeArePaidOnceAfterTheConfirmationDelay() throws Exception {
        long before = points(holder);
        long lateBefore = points(lateBuyer);
        SeasonAward a = awards.register("admin", "2026", "lg", Award.CHAMPION, recordAt, URL);
        assertThat(a.dps()).isEqualTo(3_000);

        assertThat(awards.payDue()).isZero();
        clock.advance(Duration.ofHours(6));
        assertThat(awards.payDue()).isEqualTo(1);
        assertThat(awards.payDue()).isZero();

        assertThat(points(holder)).isEqualTo(before + 10 * 3_000);
        assertThat(points(lateBuyer)).isEqualTo(lateBefore);
        SeasonAward paid = awards.latest(1).getFirst();
        assertThat(paid.status()).isEqualTo(Status.PAID);
        assertThat(paid.decidedBy()).isEqualTo(SeasonAwardService.SYSTEM);
        assertThat(paid.shares()).isEqualTo(10);
        assertThat(paid.holders()).isEqualTo(1);
        assertThat(reconciliation.run().balanced()).isTrue();

        mvc.perform(get("/api/accounts/me/dividends").with(login(holder)))
                .andExpect(jsonPath("$[0].kind").value("CHAMPION"))
                .andExpect(jsonPath("$[0].awardId").value(a.id()))
                .andExpect(jsonPath("$[0].matchId").doesNotExist())
                .andExpect(jsonPath("$[0].shares").value(10))
                .andExpect(jsonPath("$[0].amount").value(30_000));
    }

    @Test
    void eachTeamHasOneFinalPlacementAndEachTitleOneTeam() {
        awards.register("admin", "2026", "lg", Award.CHAMPION, recordAt, URL);
        assertThatThrownBy(() -> awards.register("admin", "2026", "kia", Award.CHAMPION, recordAt, URL))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> awards.register("admin", "2026", "lg", Award.SEMIFINAL, recordAt, URL))
                .isInstanceOf(IdempotencyConflictException.class);
        // 정규 시즌 1위는 최종 성적과 따로 받고, 다른 리그·시즌은 따로 센다
        awards.register("admin", "2026", "lg", Award.REGULAR_FIRST, recordAt, URL);
        awards.register("admin", "2026", "t1", Award.CHAMPION, recordAt, URL);
        awards.register("admin", "2027", "kia", Award.CHAMPION, recordAt, URL);
        awards.register("admin", "2026", "kia", Award.SEMIFINAL, recordAt, URL);
        awards.register("admin", "2026", "samsung", Award.SEMIFINAL, recordAt, URL);

        assertThatThrownBy(() -> awards.register("admin", "2026", "doosan", Award.POSTSEASON,
                clock.instant().plusSeconds(1), URL)).hasMessageContaining("지난 시각");
        assertThatThrownBy(() -> awards.register("admin", "2026", "doosan", Award.POSTSEASON, recordAt,
                "http://x")).hasMessageContaining("근거");
    }

    @Test
    void aCanceledAwardFreesItsSlotAndAPaidAwardCannotBeCanceled() {
        SeasonAward wrong = awards.register("admin", "2026", "lg", Award.CHAMPION, recordAt, URL);
        assertThat(awards.cancel(wrong.id(), "admin2").status()).isEqualTo(Status.VOID);
        clock.advance(Duration.ofHours(7));
        assertThat(awards.payDue()).isZero();

        SeasonAward right = awards.register("admin", "2026", "kia", Award.CHAMPION, recordAt, URL);
        assertThat(awards.pay(right.id(), "admin2").status()).isEqualTo(Status.PAID);
        assertThat(awards.pay(right.id(), "admin2")).isNull();
        assertThatThrownBy(() -> awards.cancel(right.id(), "admin2")).hasMessageContaining("이미 지급");
    }

    @Test
    void concurrentPaymentsPayOnce() throws Exception {
        long before = points(holder);
        SeasonAward a = awards.register("admin", "2026", "lg", Award.RUNNER_UP, recordAt, URL);
        List<Future<SeasonAward>> done = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(6)) {
            for (int i = 0; i < 6; i++) done.add(pool.submit(() -> awards.pay(a.id(), "admin")));
            long paid = 0;
            for (Future<SeasonAward> f : done) if (f.get() != null) paid++;
            assertThat(paid).isEqualTo(1);
        }
        assertThat(points(holder)).isEqualTo(before + 10 * 1_800);
        assertThat(reconciliation.run().balanced()).isTrue();
    }

    @Test
    void onlyAdminsRegisterAndAnyoneSeesTheListWithoutAdminIds() throws Exception {
        String body = """
                {"season":"2026","teamId":"lg","award":"CHAMPION","recordAt":"%s","sourceUrl":"%s"}"""
                .formatted(recordAt, URL);
        mvc.perform(post("/api/admin/awards").with(login(holder)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/awards").with(admin(holder)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dps").value(3_000))
                .andExpect(jsonPath("$.status").value("PENDING"));
        String list = mvc.perform(get("/api/awards"))
                .andExpect(jsonPath("$[0].award").value("CHAMPION"))
                .andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain(holder);
    }
}
