package com.teamtrading.leaderboard;

import com.teamtrading.ledger.Journal;
import com.teamtrading.leaderboard.DailyCloseStore.Snapshot;
import com.teamtrading.points.PointSource;
import com.teamtrading.team.Team;
import com.teamtrading.team.TeamCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 일 마감. KST 자정마다 전날의 구단 종가와 계정별 평가금·외부 유입을 남긴다.
 * <ul>
 * <li>종가는 마감 전 10분 거래량가중평균가(VWAP). 거래가 적은 구단에서 마감 직전 소량 체결로 종가를 움직이는 일을 어렵게 한다.
 * 그 사이 체결이 없으면 마지막 체결가, 체결이 한 번도 없으면 기준가.</li>
 * <li>외부 유입은 활동 보상 포인트와 지급받은 주식(그날 종가로 평가)이다. 주식 지급을 빼면 받은 주식이 수익으로 잡힌다.</li>
 * <li>평가금은 원장을 마감 시각까지 더해 되짚는다. 서버가 자정에 멈춰 있었어도 기동 후 빠진 날을 채우면 같은 값이 나온다.</li>
 * <li>날짜별로 한 번만 쓴다(기본키). 두 곳에서 동시에 돌아도 한쪽은 중복 키로 롤백된다.</li>
 * </ul>
 */
@Service
public class DailyCloses {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final Duration CLOSE_WINDOW = Duration.ofMinutes(10);
    private static final int MAX_CATCH_UP_DAYS = 400;
    private static final Logger log = LoggerFactory.getLogger(DailyCloses.class);

    private final DailyCloseStore store;
    private final TeamCatalog catalog;
    private final TransactionTemplate tx;
    private final Clock clock;

    DailyCloses(DailyCloseStore store, TeamCatalog catalog, TransactionTemplate tx, Clock clock) {
        this.store = store;
        this.catalog = catalog;
        this.tx = tx;
        this.clock = clock;
    }

    static Instant startOf(LocalDate date) {
        return date.atStartOfDay(KST).toInstant();
    }

    LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), KST);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron = "0 1 0 * * *", zone = "Asia/Seoul")
    public void scheduled() {
        try {
            catchUp();
        } catch (RuntimeException e) {
            log.error("daily close failed", e);
        }
    }

    /** 아직 마감하지 않은 지난 날을 모두 마감한다. 마감한 날 수를 돌려준다 */
    public int catchUp() {
        LocalDate yesterday = today().minusDays(1);
        LocalDate start = store.lastClosedDate().map(d -> d.plusDays(1))
                .or(() -> store.firstAccountCreatedAt().map(t -> LocalDate.ofInstant(t, KST)))
                .orElse(yesterday.plusDays(1));
        if (start.isBefore(yesterday.minusDays(MAX_CATCH_UP_DAYS))) start = yesterday.minusDays(MAX_CATCH_UP_DAYS);
        int closed = 0;
        for (LocalDate d = start; !d.isAfter(yesterday); d = d.plusDays(1)) if (close(d)) closed++;
        return closed;
    }

    /** 하루를 마감한다. 이미 마감했으면 false */
    public boolean close(LocalDate date) {
        if (!date.isBefore(today())) throw new IllegalArgumentException("day not over yet: " + date);
        try {
            return Boolean.TRUE.equals(tx.execute(s -> {
                if (store.closed(date)) return false;
                Instant closeAt = startOf(date.plusDays(1));
                Map<String, Long> prices = new HashMap<>();
                for (Team t : catalog.all()) {
                    long price = store.closePrice(t.id(), closeAt.minus(CLOSE_WINDOW), closeAt).orElse(t.basePrice());
                    prices.put(t.id(), price);
                    store.insertClose(t.id(), date, price);
                }
                store.insertSnapshots(date, snapshots(prices, startOf(date), closeAt));
                return true;
            }));
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    private Map<String, Snapshot> snapshots(Map<String, Long> prices, Instant dayStart, Instant closeAt) {
        Map<String, Map<String, Long>> balances = store.balancesBefore(closeAt);
        Map<String, Long> inflows = inflows(dayStart, closeAt, prices);
        Set<String> accounts = new HashSet<>(balances.keySet());
        accounts.addAll(inflows.keySet());
        Map<String, Snapshot> out = new HashMap<>();
        for (String account : accounts) {
            long equity = 0;
            for (var b : balances.getOrDefault(account, Map.of()).entrySet()) {
                equity += b.getKey().equals(Journal.POINT) ? b.getValue() : b.getValue() * prices.getOrDefault(b.getKey(), 0L);
            }
            out.put(account, new Snapshot(equity, inflows.getOrDefault(account, 0L)));
        }
        return out;
    }

    /** 외부 유입: 활동 보상 포인트 + 지급받은 주식(그날 가격으로 평가) */
    Map<String, Long> inflows(Instant from, Instant to, Map<String, Long> prices) {
        Map<String, Long> out = new HashMap<>(store.inflows(inflowSources(), from, to));
        store.grantedShares(from, to).forEach((account, teams) -> teams.forEach((team, qty) ->
                out.merge(account, qty * prices.getOrDefault(team, 0L), Long::sum)));
        return out;
    }

    static List<String> inflowSources() {
        return Arrays.stream(PointSource.values()).filter(PointSource::inflow).map(Enum::name).toList();
    }
}
