package com.fanchart.ad;

import com.jayway.jsonpath.JsonPath;
import com.fanchart.TestClock;
import com.fanchart.account.AccountStore;
import com.fanchart.ledger.Reconciliation;
import com.fanchart.points.PointSource;
import com.fanchart.trading.TradingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.fanchart.auth.TestLogin.login;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"fanchart.ads.admob-ad-units=ca-app-pub-1/111", "fanchart.ads.demo=true"})
@AutoConfigureMockMvc
@Import({TestClock.Config.class, AdRewardTest.Keys.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AdRewardTest {

    static final long KEY_ID = 7;
    static final KeyPair GOOGLE = generate();
    static final KeyPair FORGER = generate();
    static final String AD_UNIT = "ca-app-pub-1/111";

    @TestConfiguration
    static class Keys {
        @Bean
        @Primary
        AdMobKeys testKeys() {
            return id -> id == KEY_ID ? Optional.of(GOOGLE.getPublic()) : Optional.empty();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired TradingService trading;
    @Autowired AccountStore accounts;
    @Autowired AdRewardService ads;
    @Autowired Reconciliation reconciliation;
    @Autowired TestClock clock;

    private String viewer;

    @BeforeEach
    void setUp() {
        // 23:30 KST에서 시작하면 테스트 중에 날짜가 바뀐다. 한낮으로 옮긴다
        clock.set(clock.instant().plus(Duration.ofHours(12)));
        viewer = trading.openAccount("viewer").id();
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** AdMob이 보내는 모양 그대로: signature와 key_id가 마지막 두 파라미터, 그 앞까지가 서명 대상 */
    static String callback(KeyPair key, String adUnit, String session, String transactionId) throws Exception {
        String content = "ad_network=5450213213286189855&ad_unit=" + adUnit.replace("/", "%2F") + "&custom_data="
                + session + "&reward_amount=1&reward_item=point&timestamp=1762100000000&transaction_id="
                + transactionId + "&user_id=anything";
        Signature ecdsa = Signature.getInstance("SHA256withECDSA");
        ecdsa.initSign(key.getPrivate());
        ecdsa.update(content.getBytes(StandardCharsets.UTF_8));
        String sig = Base64.getUrlEncoder().withoutPadding().encodeToString(ecdsa.sign());
        return content + "&signature=" + sig + "&key_id=" + KEY_ID;
    }

    private ResultActions send(String query) throws Exception {
        // URI로 넘겨야 MockMvc가 쿼리 문자열을 다시 인코딩하지 않는다(서명 대상은 받은 그대로의 문자열)
        return mvc.perform(get(URI.create("/api/ads/admob/callback?" + query)));
    }

    private String open(String account) throws Exception {
        String body = mvc.perform(post("/api/accounts/me/ads").with(login(account)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private long points(String account) {
        return accounts.find(account).orElseThrow().availablePoints();
    }

    @Test
    void aSignedCallbackPaysOnceAndRetriesOfTheSameTransactionDoNotPayAgain() throws Exception {
        long before = points(viewer);
        String session = open(viewer);
        String query = callback(GOOGLE, AD_UNIT, session, "tx-1");

        send(query).andExpect(status().isOk());
        send(query).andExpect(status().isOk());

        assertThat(points(viewer)).isEqualTo(before + PointSource.AD_VIEW.amount());
        mvc.perform(get("/api/accounts/me/ads/" + session).with(login(viewer)))
                .andExpect(jsonPath("$.status").value("PAID"));
        mvc.perform(get("/api/accounts/me/ads").with(login(viewer)))
                .andExpect(jsonPath("$.paidToday").value(1))
                .andExpect(jsonPath("$.dailyLimit").value(PointSource.AD_VIEW.dailyLimit()));
        // 같은 세션에 다른 거래로 또 보상을 받을 수 없다
        send(callback(GOOGLE, AD_UNIT, session, "tx-2")).andExpect(status().isConflict());
        assertThat(points(viewer)).isEqualTo(before + PointSource.AD_VIEW.amount());
        assertThat(reconciliation.run().balanced()).isTrue();
    }

    @Test
    void forgedTamperedOrForeignCallbacksPayNothing() throws Exception {
        long before = points(viewer);
        String session = open(viewer);

        send(callback(FORGER, AD_UNIT, session, "tx-1")).andExpect(status().isForbidden());
        // 서명된 내용을 바꾸면(다른 세션으로 돌리기) 서명이 맞지 않는다
        String other = open(viewer);
        send(callback(GOOGLE, AD_UNIT, session, "tx-1").replace(session, other)).andExpect(status().isForbidden());
        // 구글 서명은 맞지만 우리가 등록하지 않은 광고 단위(다른 앱이 우리 콜백 주소를 쓴 경우)
        send(callback(GOOGLE, "ca-app-pub-9/999", session, "tx-1")).andExpect(status().isForbidden());
        send("ad_unit=x&custom_data=" + session + "&transaction_id=t").andExpect(status().isForbidden());

        assertThat(points(viewer)).isEqualTo(before);
        mvc.perform(get("/api/accounts/me/ads/" + session).with(login(viewer)))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void sessionsArePrivateAndExpire() throws Exception {
        String session = open(viewer);
        String stranger = trading.openAccount("stranger").id();
        mvc.perform(get("/api/accounts/me/ads/" + session).with(login(stranger))).andExpect(status().isNotFound());
        mvc.perform(post("/api/accounts/me/ads").with(csrf())).andExpect(status().isUnauthorized());

        clock.advance(AdRewardService.SESSION_TTL.plusSeconds(1));
        long before = points(viewer);
        send(callback(GOOGLE, AD_UNIT, session, "tx-late")).andExpect(status().isUnprocessableContent());
        assertThat(points(viewer)).isEqualTo(before);
    }

    @Test
    void theDailyLimitStopsNewSessionsAndCallbacksPastItPayNothing() throws Exception {
        int limit = PointSource.AD_VIEW.dailyLimit();
        String late = open(viewer);
        for (int i = 0; i < limit; i++)
            send(callback(GOOGLE, AD_UNIT, open(viewer), "tx-" + i)).andExpect(status().isOk());
        long paid = points(viewer);

        mvc.perform(post("/api/accounts/me/ads").with(login(viewer)))
                .andExpect(status().isUnprocessableContent());
        // 한도 전에 열어 둔 세션의 콜백은 받되 지급하지 않는다
        send(callback(GOOGLE, AD_UNIT, late, "tx-late")).andExpect(status().isOk());
        assertThat(points(viewer)).isEqualTo(paid);
        mvc.perform(get("/api/accounts/me/ads/" + late).with(login(viewer)))
                .andExpect(jsonPath("$.status").value("LIMIT"));

        // 다음 날(KST)이면 다시 열 수 있다
        clock.advance(Duration.ofDays(1));
        open(viewer);
    }

    @Test
    void concurrentCallbacksForOneSessionPayOnce() throws Exception {
        long before = points(viewer);
        String session = open(viewer);
        List<Future<?>> done = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 8; i++) {
                String tx = "tx-" + (i % 2);
                done.add(pool.submit(() -> {
                    try {
                        ads.complete(session, "admob:" + tx);
                    } catch (RuntimeException ignored) {
                        // 다른 거래로 온 콜백은 거절된다
                    }
                    return null;
                }));
            }
            for (Future<?> f : done) f.get();
        }
        assertThat(points(viewer)).isEqualTo(before + PointSource.AD_VIEW.amount());
        assertThat(reconciliation.run().balanced()).isTrue();
    }

    @Test
    void theDemoAdPaysOnlyAfterWatchingLongEnough() throws Exception {
        long before = points(viewer);
        String session = open(viewer);
        mvc.perform(post("/api/accounts/me/ads/" + session + "/demo-complete").with(login(viewer)))
                .andExpect(status().isUnprocessableContent());
        clock.advance(Duration.ofSeconds(15));
        mvc.perform(post("/api/accounts/me/ads/" + session + "/demo-complete").with(login(viewer)))
                .andExpect(jsonPath("$.status").value("PAID"));
        assertThat(points(viewer)).isEqualTo(before + PointSource.AD_VIEW.amount());
    }
}
