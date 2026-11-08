package com.fanchart.match;

import com.fanchart.match.Match.DividendStatus;
import com.fanchart.match.Match.Status;
import com.fanchart.points.PointIssuer;
import com.fanchart.team.Competition;
import com.fanchart.team.League;
import com.fanchart.team.Team;
import com.fanchart.team.TeamCatalog;
import com.fanchart.trading.NotFoundException;
import com.fanchart.trading.TradingException;
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
import java.util.Optional;
import java.util.stream.Stream;

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

    /** 같은 리그 두 구단의 정규 시즌 경기 */
    public Match schedule(String homeTeamId, String awayTeamId, Instant scheduledAt, String source, String sourceRef) {
        return schedule(null, homeTeamId, null, awayTeamId, null, scheduledAt, source, sourceRef);
    }

    /**
     * 일정 등록. 같은 출처의 같은 경기면 새로 만들지 않고, 아직 시작 전이면 시각만 고친다(연기).
     * <ul>
     * <li>리그 정규 시즌은 두 팀 모두 그 리그 구단이어야 한다.</li>
     * <li>컵대회는 리그가 달라도 되고, 한쪽은 상장하지 않은 팀(구단 id null, 이름만)이어도 된다. 그 팀에는 배당이 없다.</li>
     * </ul>
     *
     * @param competition null이면 두 구단 소속 리그의 정규 시즌
     */
    public Match schedule(Competition competition, String homeTeamId, String homeName, String awayTeamId,
                          String awayName, Instant scheduledAt, String source, String sourceRef) {
        Team home = team(homeTeamId);
        Team away = team(awayTeamId);
        if (home == null && away == null) throw new TradingException("한쪽은 상장한 구단이어야 해요");
        if (home != null && home.equals(away)) throw new TradingException("같은 구단끼리는 경기할 수 없어요");
        Competition comp = competition;
        if (comp == null) {
            if (home == null || away == null) throw new TradingException("상장하지 않은 팀과의 경기는 컵대회를 정해 주세요");
            comp = Competition.of(home.league());
        }
        if (!comp.cup() && (home == null || away == null || home.league() != comp.league()
                || away.league() != comp.league()))
            throw new TradingException("다른 리그 구단끼리는 경기할 수 없어요");
        String hName = home == null ? externalName(homeName) : null;
        String aName = away == null ? externalName(awayName) : null;
        League league = (home != null ? home : away).league();
        Competition c = comp;
        return tx.execute(s -> {
            var existing = store.findBySource(source, sourceRef);
            if (existing.isPresent()) {
                Match m = existing.get();
                if (!Objects.equals(m.homeTeamId(), homeTeamId) || !Objects.equals(m.awayTeamId(), awayTeamId)
                        || m.competition() != c)
                    throw new TradingException("같은 경기 번호에 다른 구단이나 대회가 들어왔어요");
                if (!m.scheduledAt().equals(scheduledAt)) store.reschedule(m.id(), scheduledAt, clock.instant());
                return store.find(m.id()).orElseThrow();
            }
            long id = store.insert(league, c, homeTeamId, hName, awayTeamId, aName, scheduledAt, source, sourceRef,
                    clock.instant());
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
        // 상장하지 않은 팀이 이기면 받을 구단이 없다. 무승부면 상장한 쪽만 받는다
        boolean draw = m.draw();
        List<String> teams = draw ? Stream.of(m.homeTeamId(), m.awayTeamId()).filter(Objects::nonNull).toList()
                : Stream.ofNullable(m.winner()).toList();
        String kind = draw ? "DRAW" : "WIN";

        List<Payee> payees = new ArrayList<>();
        for (String team : teams) {
            // 대회 배당은 구단의 소속 리그 승리 배당 × 대회 배수라 리그가 다른 두 구단의 무승부는 금액이 다를 수 있다
            League league = catalog.find(team).map(Team::league).orElse(m.league());
            long dps = draw ? DividendPolicy.drawDps(league, m.competition())
                    : DividendPolicy.winDps(league, m.competition());
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
        log.info("match {} {} dividend paid: {} {} to {} holdings", m.id(), m.competition(), kind, teams, payees.size());
    }

    /** 출처의 경기 번호로 찾는다 */
    public Optional<Match> findBySource(String source, String sourceRef) {
        return store.findBySource(source, sourceRef);
    }

    public Match match(long id) {
        return store.find(id).orElseThrow(() -> new NotFoundException("없는 경기예요"));
    }

    public List<Match> latest(int limit) {
        return store.latest(limit);
    }

    public List<Match> schedule(League league, Instant from, Instant to) {
        return store.between(league, from, to);
    }

    public List<Match> startedWithoutResult(String source, Instant startedBefore) {
        return store.startedWithoutResult(source, startedBefore);
    }

    public List<Match> recent(String teamId, int limit) {
        return store.recent(teamId, limit);
    }

    /** 구단의 다가오는 경기(공시 화면) */
    public List<Match> upcoming(String teamId, int limit) {
        return store.upcoming(teamId, clock.instant(), limit);
    }

    public List<DividendRow> dividends(long matchId) {
        return store.dividends(matchId);
    }

    public List<MyDividend> myDividends(String accountId, int limit) {
        return store.myDividends(accountId, limit);
    }

    private Team team(String id) {
        return id == null ? null : catalog.find(id).orElseThrow(() -> new NotFoundException("없는 구단이에요: " + id));
    }

    private static String externalName(String name) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.length() > 60) throw new TradingException("상장하지 않은 상대 팀 이름(60자 이내)이 필요해요");
        return n;
    }
}
