package com.fanchart.demo;

import com.fanchart.match.Match;
import com.fanchart.match.MatchService;
import com.fanchart.team.League;
import com.fanchart.team.Team;
import com.fanchart.team.TeamCatalog;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * 시연용 가상 경기. 실제 경기 데이터 연동 전에도 배당 흐름(일정 → 시작 시각 보유 → 결과 → 확정 대기 → 지급)을
 * 몇 분 안에 끝까지 볼 수 있게 한다. 출처를 demo로 남겨 실제 경기와 구분한다. demo 프로필에서만 켜진다.
 */
@Configuration
@Profile("demo")
@EnableScheduling
class DemoMatches {

    static final String SOURCE = "demo";
    private static final Duration LEAD = Duration.ofMinutes(1);
    private static final Duration LENGTH = Duration.ofMinutes(2);

    private final MatchService matches;
    private final List<Team> teams;
    private final Clock clock;
    private final Random random = new Random();

    DemoMatches(MatchService matches, TeamCatalog catalog, Clock clock) {
        this.matches = matches;
        this.teams = catalog.all();
        this.clock = clock;
    }

    /** 리그마다 하나씩 1분 뒤 시작하는 경기를 잡는다 */
    @Scheduled(fixedDelay = 180_000, initialDelay = 5_000)
    void scheduleNext() {
        Instant kickoff = clock.instant().plus(LEAD);
        for (League league : League.values()) {
            List<Team> pool = new ArrayList<>(teams.stream().filter(t -> t.league() == league).toList());
            if (pool.size() < 2) continue;
            Team home = pool.remove(random.nextInt(pool.size()));
            Team away = pool.get(random.nextInt(pool.size()));
            matches.schedule(home.id(), away.id(), kickoff, SOURCE, UUID.randomUUID().toString());
        }
    }

    /** 시작하고 2분 지난 경기에 결과를 넣는다. 지급은 확정 대기 뒤 배당 스케줄러가 한다 */
    @Scheduled(fixedDelay = 20_000, initialDelay = 10_000)
    void finish() {
        for (Match m : matches.startedWithoutResult(SOURCE, clock.instant().minus(LENGTH))) {
            int base = m.league() == League.NBA ? 90 : 0;
            int max = m.league() == League.KBO ? 10 : m.league() == League.LCK ? 3 : m.league() == League.NBA ? 40 : 4;
            int home = base + random.nextInt(max);
            int away = base + random.nextInt(max);
            // LCK와 NBA는 무승부가 없다
            if (m.league() == League.LCK && home == away) home = (away + 1) % (max + 1);
            if (m.league() == League.NBA && home == away) home++;
            matches.recordResult(m.id(), home, away);
        }
    }
}
