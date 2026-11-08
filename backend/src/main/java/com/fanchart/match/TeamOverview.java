package com.fanchart.match;

import com.fanchart.team.Competition;
import com.fanchart.team.League;
import com.fanchart.team.Team;
import com.fanchart.team.TeamCatalog;
import com.fanchart.trading.NotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 구단 화면 '구단정보' 탭. 이용자가 사기 전에 알아야 할 배당 조건과 다가오는 경기를 배당 규칙(DividendPolicy)에서 바로 계산한다.
 * 운영자가 글로 옮겨 적지 않으므로 규칙과 화면 숫자가 어긋나지 않는다
 */
@Service
public class TeamOverview {

    /**
     * @param shares              지금 이용자들이 가진 주식 수(판매 주문에 묶인 몫 포함)
     * @param holders             그 주식을 가진 계정 수
     * @param winDps              소속 리그 경기 1승의 주당 배당
     * @param confirmDelayMinutes 결과가 들어온 뒤 지급까지 기다리는 시간
     * @param competitions        나갈 수 있는 대회별 배당(정규 시즌이 먼저)
     * @param awards              소속 리그 성적 수당(대회 배수 1). 컵대회는 대회 배수를 곱한다
     */
    public record Overview(String teamId, League league, long shares, int holders, long winDps,
                           long confirmDelayMinutes, List<CompetitionTerms> competitions, List<AwardTerms> awards,
                           List<Upcoming> upcoming) {
    }

    public record CompetitionTerms(Competition competition, double weight, long winDps, long championDps) {
    }

    public record AwardTerms(SeasonAward.Award award, long dps) {
    }

    /** 상대가 상장하지 않은 팀이면 opponentId는 null이고 이름만 있다 */
    public record Upcoming(long matchId, Competition competition, Instant scheduledAt, boolean home,
                           String opponentId, String opponentName, long winDps) {
    }

    private static final int UPCOMING = 5;

    private final TeamCatalog catalog;
    private final MatchService matches;
    private final DividendProperties dividends;
    private final JdbcTemplate jdbc;

    TeamOverview(TeamCatalog catalog, MatchService matches, DividendProperties dividends, JdbcTemplate jdbc) {
        this.catalog = catalog;
        this.matches = matches;
        this.dividends = dividends;
        this.jdbc = jdbc;
    }

    public Overview of(String teamId) {
        Team team = catalog.find(teamId).orElseThrow(() -> new NotFoundException("없는 구단이에요"));
        League league = team.league();
        var shares = jdbc.queryForMap("""
                SELECT COALESCE(SUM(available + locked), 0) AS shares, COUNT(*) AS holders FROM positions
                WHERE team_id = ? AND available + locked > 0""", teamId);
        List<CompetitionTerms> competitions = Competition.enterable(league).stream()
                .map(c -> new CompetitionTerms(c, c.weight(), DividendPolicy.winDps(league, c),
                        DividendPolicy.awardDps(SeasonAward.Award.CHAMPION, c)))
                .toList();
        List<AwardTerms> awards = Arrays.stream(SeasonAward.Award.values())
                .map(a -> new AwardTerms(a, DividendPolicy.awardDps(a, Competition.of(league))))
                .toList();
        List<Upcoming> upcoming = matches.upcoming(teamId, UPCOMING).stream().map(m -> {
            boolean home = teamId.equals(m.homeTeamId());
            String opponentId = home ? m.awayTeamId() : m.homeTeamId();
            String opponentName = opponentId == null ? (home ? m.awayName() : m.homeName())
                    : catalog.find(opponentId).map(Team::name).orElse(opponentId);
            // 지급(MatchService.pay)과 같은 식: 우리 구단 소속 리그 승리 배당 × 대회 배수
            return new Upcoming(m.id(), m.competition(), m.scheduledAt(), home, opponentId, opponentName,
                    DividendPolicy.winDps(league, m.competition()));
        }).toList();
        return new Overview(teamId, league, ((Number) Objects.requireNonNull(shares.get("shares"))).longValue(),
                ((Number) Objects.requireNonNull(shares.get("holders"))).intValue(), DividendPolicy.winDps(league),
                dividends.confirmDelay().toMinutes(), competitions, awards, upcoming);
    }
}
