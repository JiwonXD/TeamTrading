package com.teamtrading.match;

import com.teamtrading.match.SeasonAward.Award;
import com.teamtrading.match.SeasonAward.Status;
import com.teamtrading.points.PointIssuer;
import com.teamtrading.team.Team;
import com.teamtrading.team.TeamCatalog;
import com.teamtrading.trading.IdempotencyConflictException;
import com.teamtrading.trading.NotFoundException;
import com.teamtrading.trading.TradingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 대회 최종 성적 수당(docs/adr/0020).
 * <ul>
 * <li>운영자가 리그·시즌·구단·성적·기준 시점·근거 주소를 등록한다. 기준 시점은 지난 시각이어야 한다(결과를 알고 사는 일을 막는다).</li>
 * <li>최종 성적(우승·준우승·4강·포스트시즌)은 구단당 시즌에 하나, 우승·준우승·정규 시즌 1위는 리그 시즌에 한 구단이다. DB 고유 키로 막는다.</li>
 * <li>경기 배당과 같은 확정 대기(기본 6시간)가 지나면 지급한다. 그 사이 운영자가 취소하거나 바로 지급할 수 있다.</li>
 * <li>지급은 기준 시점 보유를 원장에서 되짚어 계정 id 순서로 한다. 멱등키 award:id:계정이라 다시 돌려도 두 번 주지 않는다.</li>
 * </ul>
 */
@Service
public class SeasonAwardService {

    static final String SYSTEM = "SYSTEM";
    private static final Pattern SEASON = Pattern.compile("[0-9A-Za-z-]{4,10}");
    private static final Logger log = LoggerFactory.getLogger(SeasonAwardService.class);

    private final SeasonAwardStore store;
    private final MatchStore matches;
    private final TeamCatalog catalog;
    private final PointIssuer points;
    private final TransactionTemplate tx;
    private final DividendProperties props;
    private final Clock clock;

    SeasonAwardService(SeasonAwardStore store, MatchStore matches, TeamCatalog catalog, PointIssuer points,
                       TransactionTemplate tx, DividendProperties props, Clock clock) {
        this.store = store;
        this.matches = matches;
        this.catalog = catalog;
        this.points = points;
        this.tx = tx;
        this.props = props;
        this.clock = clock;
    }

    public SeasonAward register(String adminId, String season, String teamId, Award award, Instant recordAt,
                                String sourceUrl) {
        Team team = catalog.find(teamId).orElseThrow(() -> new NotFoundException("없는 구단이에요"));
        if (season == null || !SEASON.matcher(season).matches()) throw new TradingException("시즌은 2026처럼 4~10자예요");
        if (sourceUrl == null || !sourceUrl.startsWith("https://") || sourceUrl.length() > 300)
            throw new TradingException("근거 주소(https://)를 남겨 주세요");
        Instant now = clock.instant();
        if (recordAt == null || recordAt.isAfter(now))
            throw new TradingException("기준 시점은 이미 지난 시각이어야 해요");
        try {
            long id = store.insert(team.league(), season, teamId, award, DividendPolicy.awardDps(award), recordAt,
                    sourceUrl, adminId, now);
            log.info("season award {} registered by {}: {} {} {} {}", id, adminId, team.league(), season, teamId, award);
            return store.find(id).orElseThrow();
        } catch (DuplicateKeyException e) {
            throw new IdempotencyConflictException(
                    "이미 등록된 성적이에요. 최종 성적은 구단당 하나, 우승·준우승·정규 시즌 1위는 리그 시즌에 한 구단이에요");
        }
    }

    /** 확정 대기가 끝난 수당을 지급한다. 한 건이 실패해도 나머지는 계속한다 */
    public int payDue() {
        int paid = 0;
        for (long id : store.due(clock.instant().minus(props.confirmDelay()))) {
            try {
                if (pay(id, SYSTEM) != null) paid++;
            } catch (RuntimeException e) {
                log.error("season award {} failed", id, e);
            }
        }
        return paid;
    }

    /** 지금 지급한다. 이미 지급했거나 취소한 건이면 null */
    public SeasonAward pay(long id, String deciderId) {
        return tx.execute(s -> {
            SeasonAward a = store.lock(id).orElseThrow(() -> new NotFoundException("없는 수당이에요"));
            if (a.status() != Status.PENDING) return null;
            Map<String, Long> holders = matches.holdersAt(a.teamId(), a.recordAt());
            long shares = 0;
            // holdersAt은 계정 id 순서라 체결 정산과 같은 순서로 계정 행을 잠근다
            for (var h : holders.entrySet()) {
                points.grantDividend(h.getKey(), "award:" + a.id() + ":" + h.getKey(), h.getValue() * a.dps());
                shares += h.getValue();
            }
            store.paid(id, deciderId, shares, holders.size(), shares * a.dps(), clock.instant());
            log.info("season award {} paid by {}: DPS {} to {} holders, {} shares", id, deciderId, a.dps(),
                    holders.size(), shares);
            return store.find(id).orElseThrow();
        });
    }

    /** 지급 전이면 취소한다. 지급한 수당은 회수하지 않는다(0010) */
    public SeasonAward cancel(long id, String deciderId) {
        return tx.execute(s -> {
            SeasonAward a = store.lock(id).orElseThrow(() -> new NotFoundException("없는 수당이에요"));
            if (a.status() == Status.PAID) throw new TradingException("이미 지급한 수당은 취소할 수 없어요");
            if (a.status() == Status.PENDING) {
                store.voided(id, deciderId, clock.instant());
                log.info("season award {} canceled by {}", id, deciderId);
            }
            return store.find(id).orElseThrow();
        });
    }

    public List<SeasonAward> latest(int limit) {
        return store.latest(limit);
    }
}
