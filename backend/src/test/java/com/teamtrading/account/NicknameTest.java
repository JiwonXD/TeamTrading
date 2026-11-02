package com.teamtrading.account;

import com.teamtrading.TestClock;
import com.teamtrading.auth.AccountLogin;
import com.teamtrading.post.PostService;
import com.teamtrading.trading.TradingException;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.teamtrading.auth.TestLogin.login;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClock.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class NicknameTest {

    @Autowired MockMvc mvc;
    @Autowired NicknameService nicknames;
    @Autowired TradingService trading;
    @Autowired AccountStore accounts;
    @Autowired AccountLogin login;
    @Autowired PostService posts;
    @Autowired TransactionTemplate tx;
    @Autowired TestClock clock;

    private String me;
    /** MySQL에서는 여러 테스트가 한 DB를 쓰므로 테스트마다 다른 이름으로 연다 */
    private final String myName = "nick-" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void setUp() {
        clock.set(clock.instant().plus(Duration.ofHours(12)));
        me = trading.openAccount(myName).id();
    }

    private String nickname(String id) {
        return accounts.find(id).orElseThrow().nickname();
    }

    @Test
    void rejectsNicknamesOutsideTheRules() {
        assertThatThrownBy(() -> nicknames.change(me, "a")).hasMessageContaining("2~12자");
        assertThatThrownBy(() -> nicknames.change(me, "열세글자가넘는아주긴닉네임이에요")).hasMessageContaining("2~12자");
        assertThatThrownBy(() -> nicknames.change(me, "띄어 쓰기")).hasMessageContaining("2~12자");
        assertThatThrownBy(() -> nicknames.change(me, "ㅋㅋㅋㅋ")).hasMessageContaining("2~12자");
        assertThatThrownBy(() -> nicknames.change(me, "팀트운영자")).hasMessageContaining("운영자");
        assertThatThrownBy(() -> nicknames.change(me, "ADMIN_kim")).hasMessageContaining("운영자");
        assertThatThrownBy(() -> nicknames.change(me, myName)).hasMessageContaining("2~12자");
        assertThat(nickname(me)).isEqualTo(myName);
    }

    @Test
    void changesOncePerCooldownAndPostsShowTheNewName() {
        posts.write(me, "lg", "바꾸기 전에 쓴 글");
        Account changed = nicknames.change(me, "  LG우승가자  ");

        assertThat(changed.nickname()).isEqualTo("LG우승가자");
        assertThat(posts.list("lg", null, 10)).extracting(p -> p.nickname()).containsExactly("LG우승가자");
        assertThat(nicknames.changeableAt(changed)).isEqualTo(clock.instant().plus(Nicknames.COOLDOWN));

        assertThatThrownBy(() -> nicknames.change(me, "LG우승가자")).hasMessageContaining("지금 쓰고");
        clock.advance(Nicknames.COOLDOWN.minusSeconds(1));
        assertThatThrownBy(() -> nicknames.change(me, "다른이름")).hasMessageContaining("7일에 한 번");
        assertThat(nickname(me)).isEqualTo("LG우승가자");

        clock.advance(Duration.ofSeconds(1));
        assertThat(nicknames.changeableAt(accounts.find(me).orElseThrow())).isNull();
        assertThat(nicknames.change(me, "다른이름").nickname()).isEqualTo("다른이름");
    }

    @Test
    void aNicknameBelongsToOneAccount() {
        String other = trading.openAccount("nick-other").id();
        nicknames.change(other, "먼저가진이름");

        assertThatThrownBy(() -> nicknames.change(me, "먼저가진이름")).hasMessageContaining("다른 사람");
        assertThat(nickname(me)).isEqualTo(myName);
        // 실패한 시도는 변경 주기를 쓰지 않는다
        assertThat(nicknames.change(me, "새이름").nickname()).isEqualTo("새이름");
    }

    @Test
    void concurrentClaimsOfOneNicknameLetOnlyOneAccountHaveIt() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 8; i++) ids.add(trading.openAccount("nick-race-" + i).id());
        List<String> winners = race(ids.stream().<Callable<String>>map(id -> () -> {
            nicknames.change(id, "인기있는이름");
            return id;
        }).toList());
        assertThat(winners).hasSize(1);
        assertThat(nickname(winners.getFirst())).isEqualTo("인기있는이름");

        // 한 계정이 동시에 여러 이름으로 바꿔도 한 번만 바뀐다
        List<String> changed = race(List.<Callable<String>>of(
                () -> nicknames.change(me, "첫번째").nickname(),
                () -> nicknames.change(me, "두번째").nickname(),
                () -> nicknames.change(me, "세번째").nickname()));
        assertThat(changed).hasSize(1);
        assertThat(nickname(me)).isEqualTo(changed.getFirst());
    }

    /** 모든 작업을 한꺼번에 출발시키고, TradingException 없이 끝난 결과만 모은다 */
    private static List<String> race(List<Callable<String>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> task : tasks) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    return task.call();
                } catch (TradingException e) {
                    return null;
                }
            }));
        }
        start.countDown();
        List<String> done = new ArrayList<>();
        for (Future<String> f : futures) if (f.get() != null) done.add(f.get());
        pool.shutdown();
        return done;
    }

    @Test
    void defaultNicknamesAreUniqueAndATakenOneDoesNotBreakTheSignupTransaction() {
        Set<String> names = new HashSet<>();
        for (int i = 0; i < 30; i++) names.add(nickname(login.findOrCreate("dev", "nick-signup-" + i)));
        assertThat(names).hasSize(30).allMatch(n -> n.matches("투자자\\d{6}"));

        Instant now = clock.instant();
        tx.executeWithoutResult(s -> {
            assertThat(accounts.tryCreate("nick-dup", myName, now)).isFalse();
            assertThat(accounts.tryCreate("nick-dup", "nick-free", now)).isTrue();
        });
        assertThat(nickname("nick-dup")).isEqualTo("nick-free");
    }

    @Test
    void apiChangesMyNicknameAndTellsWhenItCanChangeAgain() throws Exception {
        mvc.perform(get("/api/accounts/me").with(login(me)))
                .andExpect(jsonPath("$.nickname").value(myName))
                .andExpect(jsonPath("$.nicknameChangeableAt").doesNotExist());

        mvc.perform(put("/api/accounts/me/nickname").with(login(me))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"새닉네임\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("새닉네임"))
                .andExpect(jsonPath("$.nicknameChangeableAt").value(clock.instant().plus(Nicknames.COOLDOWN).toString()));

        mvc.perform(put("/api/accounts/me/nickname").with(login(me))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"또바꾸기\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value("닉네임은 7일에 한 번 바꿀 수 있어요"));

        mvc.perform(put("/api/accounts/me/nickname").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"남의계정\"}"))
                .andExpect(status().isUnauthorized());
    }
}
