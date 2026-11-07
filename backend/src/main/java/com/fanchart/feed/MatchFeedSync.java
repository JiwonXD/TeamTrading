package com.fanchart.feed;

import com.fanchart.feed.FeedGame.State;
import com.fanchart.feed.MatchFeedProperties.CompetitionFeed;
import com.fanchart.match.Match;
import com.fanchart.match.MatchService;
import com.fanchart.team.Competition;
import com.fanchart.team.League;
import com.fanchart.team.Team;
import com.fanchart.team.TeamCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 제공처의 경기를 우리 경기 일정·결과에 맞춘다. 배당은 이 결과로 MatchService가 확정 대기 뒤 지급한다.
 * <ul>
 * <li>배당 기준 시점(예정 시작 시각)을 늦추는 변경은 제공처가 아직 시작 전(NOT_STARTED, POSTPONED)이라고 할 때만 받는다.
 *     경기 중에 시각만 늦게 고쳐 오면 경기를 보면서 산 주식까지 배당을 받게 되기 때문이다. 앞당기는 변경은 늘 받는다.</li>
 * <li>새 경기는 시작한 지 36시간이 안 된 것만 들인다. 오래된 시즌 기록으로 지난 배당을 새로 만들지 않는다.</li>
 * <li>이미 끝난(FINAL) 경기를 제공처가 취소라고 하거나, 취소한 경기에 결과가 오면 바꾸지 않고 운영자에게 남긴다.
 *     같은 FINAL의 점수 정정은 받는다(지급 전이면 대기를 다시 시작하고, 지급 뒤면 REVIEW).</li>
 * <li>모르는 상태(UNKNOWN)는 아무것도 하지 않는다. 리그 정규 시즌은 연결하지 않은 팀이 있는 경기를 들이지 않는다.</li>
 * <li>컵대회는 한쪽만 우리 구단이어도 들이고, 연결하지 않은 쪽은 상장하지 않은 팀(이름만)으로 둔다.
 *     그래서 우리 구단은 컵대회를 받기 전에 그 제공처 팀 번호를 먼저 이어야 한다. 양쪽 다 우리 구단이 아니면 건너뛴다.</li>
 * </ul>
 */
@Service
@EnableConfigurationProperties(MatchFeedProperties.class)
public class MatchFeedSync {

    static final Duration NEW_GAME_WINDOW = Duration.ofHours(36);
    /** 시작하고 이만큼 지나야 결과를 묻는다 */
    static final Duration FIRST_POLL = Duration.ofHours(2);
    /** 시작하고 이만큼은 스케줄러가 돌 때마다 묻고, 그 뒤에는 SLOW_POLL마다 묻는다 */
    static final Duration FAST_POLL_UNTIL = Duration.ofHours(6);
    static final Duration SLOW_POLL = Duration.ofHours(3);
    /** 이만큼 지나도 결과가 없으면 더 묻지 않는다. 운영자가 결과를 넣거나 취소한다 */
    static final Duration GIVE_UP = Duration.ofHours(48);

    private static final Logger log = LoggerFactory.getLogger(MatchFeedSync.class);

    /**
     * @param unmappedTeams 연결하지 않은 제공처 팀("번호 이름"). 리그는 그 경기를 건너뛰었고, 컵대회는 상장하지 않은 팀으로
     *                      들였다. 우리 구단이면 운영자가 이 번호로 잇는다
     * @param error         요청이 실패했으면 그 이유
     */
    public record Summary(String source, String competition, int created, int rescheduled, int finals, int canceled,
                          int ignored, int conflicts, List<String> unmappedTeams, String error) {
    }

    private final MatchFeedProperties props;
    private final FeedClient client;
    private final FeedTeams teams;
    private final TeamCatalog catalog;
    private final MatchService matches;
    private final Clock clock;
    private final Map<String, Instant> lastPolled = new ConcurrentHashMap<>();

    MatchFeedSync(MatchFeedProperties props, FeedClient client, FeedTeams teams, TeamCatalog catalog,
                  MatchService matches, Clock clock) {
        this.props = props;
        this.client = client;
        this.teams = teams;
        this.catalog = catalog;
        this.matches = matches;
        this.clock = clock;
    }

    public boolean enabled() {
        return props.enabled();
    }

    /** 설정한 대회 전부(키가 없거나 꺼져 쉬는 대회 포함) */
    public List<CompetitionFeed> competitions() {
        return props.competitions();
    }

    /** 키가 있고 켜져 있어 수집하는 대회인지 */
    public boolean active(CompetitionFeed feed) {
        return props.active(feed);
    }

    /** 대회마다 시즌 전체 일정을 받아 맞춘다. 한 대회가 실패해도 나머지는 한다 */
    public List<Summary> syncSeason() {
        List<Summary> out = new ArrayList<>();
        for (CompetitionFeed feed : props.activeFeeds()) {
            try {
                out.add(apply(feed, client.games(feed, null)));
            } catch (FeedException e) {
                log.warn("match feed {} {} season sync failed: {}", feed.source(), feed.competition(), e.getMessage());
                out.add(new Summary(feed.source(), feed.competition().name(), 0, 0, 0, 0, 0, 0, List.of(), e.getMessage()));
            }
        }
        return out;
    }

    /** 시작했는데 결과가 없는 경기의 날짜만 다시 묻는다 */
    public void pollResults() {
        if (!props.enabled()) return;
        Instant now = clock.instant();
        LocalDate today = now.atZone(FeedClient.KST).toLocalDate();
        lastPolled.keySet().removeIf(k -> LocalDate.parse(k.substring(k.lastIndexOf(':') + 1))
                .isBefore(today.minusDays(3)));
        for (CompetitionFeed feed : props.activeFeeds()) {
            Set<LocalDate> dates = new TreeSet<>();
            for (Match m : matches.startedWithoutResult(feed.source(), now.minus(FIRST_POLL))) {
                Duration since = Duration.between(m.scheduledAt(), now);
                if (m.competition() != feed.competition() || since.compareTo(GIVE_UP) > 0) continue;
                LocalDate date = m.scheduledAt().atZone(FeedClient.KST).toLocalDate();
                Instant last = lastPolled.get(pollKey(feed, date));
                Duration wait = since.compareTo(FAST_POLL_UNTIL) < 0 ? Duration.ZERO : SLOW_POLL;
                if (last == null || !last.plus(wait).isAfter(now)) dates.add(date);
            }
            for (LocalDate date : dates) {
                lastPolled.put(pollKey(feed, date), now);
                try {
                    apply(feed, client.games(feed, date));
                } catch (FeedException e) {
                    log.warn("match feed {} {} {} poll failed: {}", feed.source(), feed.competition(), date,
                            e.getMessage());
                }
            }
        }
    }

    private static String pollKey(CompetitionFeed feed, LocalDate date) {
        return feed.source() + ":" + feed.competition() + ":" + date;
    }

    /** 받은 경기를 맞춘다. 한 경기가 실패해도 나머지는 한다 */
    public synchronized Summary apply(CompetitionFeed feed, List<FeedGame> games) {
        Counts c = new Counts();
        Set<String> unmapped = new TreeSet<>();
        boolean cup = feed.competition().cup();
        for (FeedGame g : games) {
            String home = teams.teamFor(feed.source(), g.homeTeamId()).orElse(null);
            String away = teams.teamFor(feed.source(), g.awayTeamId()).orElse(null);
            // 컵대회에서 양쪽 다 우리 구단이 아니면 우리 경기가 아니다(알리지도 않는다)
            if (cup && home == null && away == null) continue;
            if (home == null) unmapped.add(g.homeTeamId() + " " + g.homeName());
            if (away == null) unmapped.add(g.awayTeamId() + " " + g.awayName());
            if (!cup && (home == null || away == null)) continue;
            try {
                sync(feed, g, home, away, c);
            } catch (RuntimeException e) {
                c.conflicts++;
                log.warn("match feed {} game {} not applied: {}", feed.source(), g.ref(), e.getMessage());
            }
        }
        Summary s = new Summary(feed.source(), feed.competition().name(), c.created, c.rescheduled, c.finals, c.canceled,
                c.ignored, c.conflicts, List.copyOf(unmapped), null);
        if (c.created + c.rescheduled + c.finals + c.canceled + c.conflicts > 0 || !unmapped.isEmpty())
            log.info("match feed {}", s);
        return s;
    }

    private static final class Counts {
        int created, rescheduled, finals, canceled, ignored, conflicts;
    }

    private void sync(CompetitionFeed feed, FeedGame g, String home, String away, Counts c) {
        Competition comp = feed.competition();
        if (!comp.cup() && (league(home) != comp.league() || league(away) != comp.league())) {
            c.conflicts++;
            log.warn("match feed {} game {}: {} vs {} is not a {} match", feed.source(), g.ref(), home, away, comp);
            return;
        }
        Instant now = clock.instant();
        Match m = matches.findBySource(feed.source(), g.ref()).orElse(null);
        if (m == null) {
            if (g.state() == State.CANCELED || g.state() == State.UNKNOWN
                    || g.scheduledAt().isBefore(now.minus(NEW_GAME_WINDOW))) {
                c.ignored++;
                return;
            }
            m = schedule(comp, g, home, away, feed.source());
            c.created++;
        } else if (!Objects.equals(m.homeTeamId(), home) || !Objects.equals(m.awayTeamId(), away)) {
            c.conflicts++;
            log.warn("match feed {} game {} changed teams: ours {} vs {}, feed {} vs {}", feed.source(), g.ref(),
                    m.homeTeamId(), m.awayTeamId(), home, away);
            return;
        } else if (m.status() == Match.Status.SCHEDULED && !m.scheduledAt().equals(g.scheduledAt())) {
            boolean earlier = g.scheduledAt().isBefore(m.scheduledAt());
            boolean notStarted = g.state() == State.NOT_STARTED || g.state() == State.POSTPONED;
            if (earlier || notStarted) {
                m = schedule(comp, g, home, away, feed.source());
                c.rescheduled++;
            } else {
                log.warn("match feed {} game {} moved later to {} while {}; keeping {}", feed.source(), g.ref(),
                        g.scheduledAt(), g.rawStatus(), m.scheduledAt());
            }
        }
        result(m, g, now, c);
    }

    private void result(Match m, FeedGame g, Instant now, Counts c) {
        switch (g.state()) {
            case FINAL -> {
                if (m.status() == Match.Status.CANCELED) {
                    c.conflicts++;
                    log.warn("match feed game {} is final {}-{} but match {} is canceled", g.ref(), g.homeScore(),
                            g.awayScore(), m.id());
                } else if (now.isBefore(m.scheduledAt())) {
                    c.ignored++;
                } else if (m.status() != Match.Status.FINAL || !Objects.equals(m.homeScore(), g.homeScore())
                        || !Objects.equals(m.awayScore(), g.awayScore())) {
                    matches.recordResult(m.id(), g.homeScore(), g.awayScore());
                    c.finals++;
                }
            }
            case CANCELED -> {
                if (m.status() == Match.Status.SCHEDULED) {
                    matches.cancel(m.id());
                    c.canceled++;
                } else if (m.status() == Match.Status.FINAL) {
                    c.conflicts++;
                    log.warn("match feed game {} is {} but match {} is final", g.ref(), g.rawStatus(), m.id());
                }
            }
            default -> {
            }
        }
    }

    private Match schedule(Competition comp, FeedGame g, String home, String away, String source) {
        return matches.schedule(comp, home, g.homeName(), away, g.awayName(), g.scheduledAt(), source, g.ref());
    }

    private League league(String teamId) {
        return catalog.find(teamId).map(Team::league).orElse(null);
    }
}
