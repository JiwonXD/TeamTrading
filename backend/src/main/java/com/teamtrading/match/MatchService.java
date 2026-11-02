package com.teamtrading.match;

import com.teamtrading.match.Match.DividendStatus;
import com.teamtrading.match.Match.Status;
import com.teamtrading.points.PointIssuer;
import com.teamtrading.team.League;
import com.teamtrading.team.Team;
import com.teamtrading.team.TeamCatalog;
import com.teamtrading.trading.NotFoundException;
import com.teamtrading.trading.TradingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 경기 일정·결과와 배당 지급.
 * <ul>
 * <li>기준 시점은 예정 경기 시작 시각이다. 그 시각에 가진 주식(매도 주문에 묶인 몫 포함)에만 배당한다.
 * 결과를 보고 사는 매매는 배당을 받지 못한다. 보유 수량은 원장을 그 시각까지 더해 되짚으므로 지급이 늦어져도 같다.</li>
 * <li>결과가 들어오면 확정 대기 시간(confirmDelay)이 지난 뒤 지급한다. 그 사이 정정이 오면 대기를 다시 시작한다.</li>
 * <li>한 경기의 지급은 한 트랜잭션이다. 경기 행을 잠그고 상태를 보고 지급하므로 두 번 지급되지 않고,
 * 계정별 지급 기록의 멱등키(경기:구단:계정)가 마지막 방어선이다.</li>
 * <li>지급한 뒤 결과가 바뀌면 자동으로 회수하지 않고 REVIEW로 표시해 운영자가 본다. 이미 쓴 포인트를 빼앗으면
 * 잔고가 음수가 되거나 체결이 취소되어야 하기 때문이다.</li>
 * </ul>
 */
@Service
@EnableConfigurationProperties(DividendProperties.class)
public class MatchService {

    private static final Logger log = LoggerFactory.getLogger(MatchService.class);

    private final MatchStore store;
    private final TeamCatalog catalog;
    private final PointIssuer points;
    private final TransactionTemplate tx;
    private final DividendProperties props;
    private final Clock clock;

    MatchService(MatchStore store, TeamCatalog catalog, PointIssuer points, TransactionTemplate tx,
                 DividendProperties props, Clock clock) {
        this.store = store;
        this.catalog = catalog;
        this.points = points;
        this.tx = tx;
        this.props = props;
        this.clock = clock;
    }

    /** 일정 등록. 같은 출처의 같은 경기면 새로 만들지 않고, 아직 시작 전이면 시각만 고친다(연기) */
    public Match schedule(String homeTeamId, String awayTeamId, Instant scheduledAt, String source, String sourceRef) {
        League league = league(homeTeamId, awayTeamId);
        return tx.execute(s -> {
            var existing = store.findBySource(source, sourceRef);
            if (existing.isPresent()) {
                Match m = existing.get();
                if (!m.homeTeamId().equals(homeTeamId) || !m.awayTeamId().equals(awayTeamId))
                    throw new TradingException("같은 경기 번호에 다른 구단이 들어왔어요");
                if (!m.scheduledAt().equals(scheduledAt)) store.reschedule(m.id(), scheduledAt, clock.instant());
                return store.find(m.id()).orElseThrow();
            }
            long id = store.insert(league, homeTeamId, awayTeamId, scheduledAt, source, sourceRef, clock.instant());
            return store.find(id).orElseThrow();
        });
    }

    public Match recordResult(long matchId, int homeScore, int awayScore) {
        if (homeScore < 0 || awayScore < 0) throw new TradingException("점수는 0 이상이어야 해요");
        return change(matchId, Status.FINAL, homeScore, awayScore);
    }

    /** 경기 취소. 배당은 없다 */
    public Match cancel(long matchId) {
        return change(matchId, Status.CANCELED, null, null);
    }

    private Match change(long matchId, Status status, Integer home, Integer away) {
        return tx.execute(s -> {
            Match m = store.lock(matchId).orElseThrow(() -> new NotFoundException("없는 경기예요"));
            Instant now = clock.instant();
            if (status == Status.FINAL && now.isBefore(m.scheduledAt()))
                throw new TradingException("시작 전인 경기에는 결과를 넣을 수 없어요");
            boolean same = m.status() == status && Objects.equals(m.homeScore(), home)
                    && Objects.equals(m.awayScore(), away);
            if (same) return m;
            store.setResult(matchId, status, home, away, now);
            if (m.dividendStatus() != DividendStatus.NONE) {
                store.setDividendStatus(matchId, DividendStatus.REVIEW, now);
                log.warn("match {} result changed after dividend {}: {} {}-{}", matchId, m.dividendStatus(),
                        status, home, away);
            }
            return store.find(matchId).orElseThrow();
        });
    }

    /** 확정 대기가 끝난 경기의 배당을 지급한다. 한 경기가 실패해도 나머지는 계속한다 */
    public int payDue() {
        int paid = 0;
        for (long id : store.due(clock.instant().minus(props.confirmDelay()))) {
            try {
                if (settle(id)) paid++;
            } catch (RuntimeException e) {
                log.error("dividend for match {} failed", id, e);
            }
        }
        return paid;
    }

    /** 확정 대기 없이 지금 지급한다(운영자 확인). 이미 처리한 경기면 false */
    public boolean settle(long matchId) {
        Boolean done = tx.execute(s -> {
            Match m = store.lock(matchId).orElseThrow(() -> new NotFoundException("없는 경기예요"));
            if (m.dividendStatus() != DividendStatus.NONE) return false;
            Instant now = clock.instant();
            switch (m.status()) {
                case SCHEDULED -> throw new TradingException("아직 결과가 없는 경기예요");
                case CANCELED -> store.setDividendStatus(matchId, DividendStatus.VOID, now);
                case FINAL -> {
                    pay(m, now);
                    store.setDividendStatus(matchId, DividendStatus.PAID, now);
                }
            }
            return true;
        });
        return Boolean.TRUE.equals(done);
    }

    private void pay(Match m, Instant now) {
        record Payee(String accountId, String teamId, long shares, long amount) {
        }
        String winner = m.winner();
        List<String> teams = winner != null ? List.of(winner) : List.of(m.homeTeamId(), m.awayTeamId());
        long dps = winner != null ? DividendPolicy.winDps(m.league()) : DividendPolicy.drawDps(m.league());
        String kind = winner != null ? "WIN" : "DRAW";

        List<Payee> payees = new ArrayList<>();
        for (String team : teams) {
            Map<String, Long> holders = store.holdersAt(team, m.scheduledAt());
            long shares = 0;
            for (var h : holders.entrySet()) {
                payees.add(new Payee(h.getKey(), team, h.getValue(), h.getValue() * dps));
                shares += h.getValue();
            }
            store.insertDividend(m.id(), team, kind, dps, m.scheduledAt(), shares, holders.size(), shares * dps, now);
        }
        // 체결 정산처럼 계정 id 순서로 갱신해 교착을 피한다
        payees.sort(Comparator.comparing(Payee::accountId).thenComparing(Payee::teamId));
        for (Payee p : payees)
            points.grantDividend(p.accountId(), m.id() + ":" + p.teamId() + ":" + p.accountId(), p.amount());
        log.info("match {} dividend paid: {} {} DPS {} to {} holdings", m.id(), kind, teams, dps, payees.size());
    }

    public Match match(long id) {
        return store.find(id).orElseThrow(() -> new NotFoundException("없는 경기예요"));
    }

    public List<Match> latest(int limit) {
        return store.latest(limit);
    }

    public List<Match> startedWithoutResult(String source, Instant startedBefore) {
        return store.startedWithoutResult(source, startedBefore);
    }

    public List<Match> recent(String teamId, int limit) {
        return store.recent(teamId, limit);
    }

    public List<DividendRow> dividends(long matchId) {
        return store.dividends(matchId);
    }

    public List<MyDividend> myDividends(String accountId, int limit) {
        return store.myDividends(accountId, limit);
    }

    private League league(String home, String away) {
        Team h = catalog.find(home).orElseThrow(() -> new NotFoundException("없는 구단이에요: " + home));
        Team a = catalog.find(away).orElseThrow(() -> new NotFoundException("없는 구단이에요: " + away));
        if (h.equals(a)) throw new TradingException("같은 구단끼리는 경기할 수 없어요");
        if (h.league() != a.league()) throw new TradingException("다른 리그 구단끼리는 경기할 수 없어요");
        return h.league();
    }
}
