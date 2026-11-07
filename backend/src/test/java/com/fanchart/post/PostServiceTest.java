package com.fanchart.post;

import com.fanchart.TestClock;
import com.fanchart.account.AccountStore;
import com.fanchart.ledger.Reconciliation;
import com.fanchart.post.Post.ReportReason;
import com.fanchart.post.Post.Reward;
import com.fanchart.trading.TradingException;
import com.fanchart.trading.TradingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestClock.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PostServiceTest {

    @Autowired PostService posts;
    @Autowired TradingService trading;
    @Autowired AccountStore accounts;
    @Autowired PostStore store;
    @Autowired Reconciliation reconciliation;
    @Autowired TestClock clock;

    private String writer;

    @BeforeEach
    void setUp() {
        // 23:30 KST에서 시작하면 보상 대기 중에 날짜가 바뀐다. 한낮으로 옮긴다
        clock.set(clock.instant().plus(Duration.ofHours(12)));
        writer = trading.openAccount("writer").id();
    }

    private Post write(String body) {
        Post p = posts.write(writer, "lg", body);
        clock.advance(Duration.ofSeconds(21));
        return p;
    }

    private long points(String id) {
        return accounts.find(id).orElseThrow().availablePoints();
    }

    @Test
    void rejectsShortLinkedRepeatedAndTooFrequentPosts() {
        assertThatThrownBy(() -> posts.write(writer, "lg", "  좋아  ")).hasMessageContaining("5자 이상");
        assertThatThrownBy(() -> posts.write(writer, "lg", "여기 보세요 https://spam.example")).hasMessageContaining("링크");
        assertThatThrownBy(() -> posts.write(writer, "lg", "가입하면 혜택 bit.ly/abc")).hasMessageContaining("링크");
        assertThatThrownBy(() -> posts.write(writer, "nope", "없는 구단에 글쓰기")).hasMessageContaining("없는 구단");

        Post p = posts.write(writer, "lg", "  오늘   선발   좋네요  ");
        assertThat(p.body()).isEqualTo("오늘 선발 좋네요");
        assertThatThrownBy(() -> posts.write(writer, "lg", "바로 또 쓰는 글")).hasMessageContaining("20초");
        clock.advance(Duration.ofSeconds(21));
        assertThatThrownBy(() -> posts.write(writer, "kt", "오늘 선발 좋네요")).hasMessageContaining("같은 글");
    }

    @Test
    void limitsPostsPerDay() {
        for (int i = 0; i < PostService.DAILY_POSTS; i++) write("오늘의 글 번호 " + i);
        assertThatThrownBy(() -> write("하나 더 쓰는 글")).hasMessageContaining("30개");
    }

    @Test
    void marksHoldersAtTheTimeOfWriting() {
        assertThat(write("아직 없는 구단 글").holder()).isFalse();
        trading.grantShares(writer, "lg", 3, 18_450);
        assertThat(write("이제 가진 구단 글").holder()).isTrue();
    }

    @Test
    void paysTheRewardAfterTheDelayUpToTheDailyLimit() {
        List<Post> written = new ArrayList<>();
        for (int i = 0; i < 4; i++) written.add(write("보상 받을 글 " + i));
        long before = points(writer);

        assertThat(posts.payRewards()).isZero();             // 아직 10분이 지나지 않았다
        clock.advance(Duration.ofMinutes(10));
        assertThat(posts.payRewards()).isEqualTo(3);
        assertThat(posts.payRewards()).isZero();

        assertThat(points(writer) - before).isEqualTo(3 * 2_000);
        assertThat(written).extracting(p -> store.find(p.id()).orElseThrow().reward())
                .containsExactly(Reward.PAID, Reward.PAID, Reward.PAID, Reward.NONE);
        assertThat(reconciliation.run().balanced()).isTrue();
    }

    @Test
    void reportsHideAPostAndHiddenOrDeletedPostsEarnNothing() {
        Post spam = write("광고성 도배 글입니다");
        Post gone = write("곧 지울 글입니다");
        List<String> reporters = new ArrayList<>();
        for (int i = 0; i < 3; i++) reporters.add(trading.openAccount("r" + i).id());

        assertThatThrownBy(() -> posts.report(writer, spam.id(), ReportReason.SPAM)).hasMessageContaining("내 글");
        posts.report(reporters.get(0), spam.id(), ReportReason.SPAM);
        posts.report(reporters.get(0), spam.id(), ReportReason.AD);   // 같은 계정은 한 번만 센다
        posts.report(reporters.get(1), spam.id(), ReportReason.SPAM);
        assertThat(posts.list("lg", null, 10)).extracting(Post::id).contains(spam.id());
        posts.report(reporters.get(2), spam.id(), ReportReason.SPAM);
        assertThat(posts.list("lg", null, 10)).extracting(Post::id).doesNotContain(spam.id());

        posts.delete(writer, gone.id());
        assertThatThrownBy(() -> posts.delete(reporters.get(0), spam.id())).isInstanceOf(RuntimeException.class);
        assertThat(posts.list("lg", null, 10)).isEmpty();

        long before = points(writer);
        clock.advance(Duration.ofMinutes(10));
        assertThat(posts.payRewards()).isZero();
        assertThat(points(writer)).isEqualTo(before);

        posts.restore(spam.id());
        assertThat(posts.list("lg", null, 10)).extracting(Post::id).containsExactly(spam.id());
        assertThat(posts.byStatus(Post.Status.DELETED, 10)).extracting(Post::id).containsExactly(gone.id());
    }

    @Test
    void listsNewestFirstWithCursor() {
        Post first = write("첫 번째 글입니다");
        Post second = write("두 번째 글입니다");
        Post third = write("세 번째 글입니다");
        assertThat(posts.list("lg", null, 2)).extracting(Post::id).containsExactly(third.id(), second.id());
        assertThat(posts.list("lg", second.id(), 2)).extracting(Post::id).containsExactly(first.id());
        assertThat(posts.list("kt", null, 10)).isEmpty();
    }

    @Test
    void concurrentPostsFromOneAccountRespectTheInterval() throws Exception {
        List<Future<?>> futures = new ArrayList<>();
        int ok = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 8; i++) {
                int n = i;
                futures.add(pool.submit(() -> posts.write(writer, "lg", "동시에 쓰는 글 " + n)));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                    ok++;
                } catch (java.util.concurrent.ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(TradingException.class);
                }
            }
        }
        assertThat(ok).isEqualTo(1);
    }
}
