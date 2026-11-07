package com.fanchart.demo;

import com.fanchart.account.Account;
import com.fanchart.offering.Offering;
import com.fanchart.offering.OfferingService;
import com.fanchart.team.Team;
import com.fanchart.team.TeamCatalog;
import com.fanchart.trading.TradingException;
import com.fanchart.trading.TradingService;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 시연용 추가 공모. 10분마다 구단 하나에 4분짜리 청약을 열고 데모 계정 몇이 청약한다.
 * 사용자는 그 사이 직접 청약해 균등·비례배정 결과를 볼 수 있다. 데모 구단은 이미 주식이 있어 추가 공모라 거래는 막지 않는다.
 * demo 프로필에서만 켜진다.
 */
@Configuration
@Profile("demo")
@EnableScheduling
class DemoOfferings {

    static final Duration WINDOW = Duration.ofMinutes(4);
    static final long TOTAL_SHARES = 100;
    static final long MAX_PER_ACCOUNT = 30;

    private final OfferingService offerings;
    private final TradingService trading;
    private final List<Team> teams;
    private final Clock clock;
    private final Random random = new Random();

    DemoOfferings(OfferingService offerings, TradingService trading, TeamCatalog catalog, Clock clock) {
        this.offerings = offerings;
        this.trading = trading;
        this.teams = catalog.all();
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 15_000)
    void openNext() {
        Instant now = clock.instant();
        List<Team> pool = new ArrayList<>(teams);
        Collections.shuffle(pool, random);
        for (Team team : pool) {
            try {
                Offering o = offerings.create(team.id(), null, TOTAL_SHARES, MAX_PER_ACCOUNT, now, now.plus(WINDOW),
                        now.plus(WINDOW));
                subscribeBots(o);
                return;
            } catch (TradingException e) {
                // 이미 청약 중인 구단이면 다음 구단
            }
        }
    }

    private void subscribeBots(Offering o) {
        Set<String> names = Set.copyOf(DemoTraders.NAMES);
        List<String> bots = new ArrayList<>(trading.allAccounts().stream()
                .filter(a -> names.contains(a.nickname())).map(Account::id).toList());
        Collections.shuffle(bots, random);
        for (String bot : bots.subList(0, Math.min(6, bots.size()))) {
            try {
                offerings.subscribe(o.id(), bot, 5 + random.nextInt(21));
            } catch (TradingException e) {
                // 청약금이 모자란 계정은 건너뛴다
            }
        }
    }
}
