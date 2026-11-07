package com.fanchart.demo;

import com.fanchart.TestClock;
import com.fanchart.ledger.Reconciliation;
import com.fanchart.match.Match;
import com.fanchart.match.MatchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("demo")
@Import(TestClock.Config.class)
class DemoMatchesTest {

    @Autowired DemoMatches demo;
    @Autowired MatchService matches;
    @Autowired Reconciliation reconciliation;
    @Autowired TestClock clock;

    @Test
    void demoMatchesGoAllTheWayToDividendPayment() {
        demo.scheduleNext();
        clock.advance(Duration.ofMinutes(4));   // 시작 1분 + 경기 2분 + 여유
        demo.finish();
        clock.advance(Duration.ofMinutes(2));   // demo 프로필 확정 대기 1분
        matches.payDue();

        List<Match> played = matches.latest(50).stream().filter(m -> m.source().equals(DemoMatches.SOURCE)).toList();
        assertThat(played).hasSizeGreaterThanOrEqualTo(3);
        assertThat(played).allSatisfy(m -> {
            assertThat(m.status()).isEqualTo(Match.Status.FINAL);
            assertThat(m.dividendStatus()).isEqualTo(Match.DividendStatus.PAID);
        });
        // 시연 계정들은 모든 구단 주식을 지급받아 가지고 있으므로 배당이 실제로 나간다
        assertThat(reconciliation.run().issuance().bySource()).containsKey("DIVIDEND");
        assertThat(reconciliation.run().balanced()).isTrue();
    }
}
