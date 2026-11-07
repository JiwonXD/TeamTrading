package com.fanchart.feed;

import com.fanchart.feed.FeedGame.State;
import com.fanchart.feed.MatchFeedProperties.CompetitionFeed;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * football-data.org v4 competitions/{코드}/matches. 무료 요금제로 프리미어리그(PL)·라리가(PD)·세리에A(SA)·
 * 분데스리가(BL1)·챔피언스리그(CL)를 받는다(분당 10회, 하루 상한 없음).
 * <ul>
 * <li>시즌을 비우면 현재 시즌을 준다. 시즌은 시작 연도다(2026 = 2026/27).</li>
 * <li>날짜 조건(dateFrom, dateTo)은 UTC 날짜라서, 한국 시간 하루를 덮도록 전날부터 묻는다. 더 받은 경기는 동기화에 해가 없다.</li>
 * <li>시작 시각은 utcDate(UTC ISO)로 읽는다. 상대가 정해지지 않은 경기(팀 번호 없음)는 건너뛴다.</li>
 * <li>몰수(AWARDED)는 점수가 경기 결과가 아니라서 UNKNOWN으로 두고 운영자가 판단한다.</li>
 * </ul>
 */
final class FootballDataProvider implements FeedProvider {

    @Override
    public URI defaultBaseUrl() {
        return URI.create("https://api.football-data.org");
    }

    @Override
    public int defaultDailyLimit() {
        return 500;
    }

    @Override
    public HttpRequest request(CompetitionFeed feed, String key, URI base, LocalDate date,
                               LocalDate today) {
        List<String> query = new ArrayList<>();
        if (feed.season() != null) query.add("season=" + feed.season());
        if (date != null) {
            query.add("dateFrom=" + date.minusDays(1));
            query.add("dateTo=" + date);
        }
        String uri = base + "/v4/competitions/" + feed.leagueId() + "/matches"
                + (query.isEmpty() ? "" : "?" + String.join("&", query));
        return HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(10))
                .header("X-Auth-Token", key).GET().build();
    }

    @Override
    public List<FeedGame> parse(JsonNode root) {
        JsonNode matches = root.path("matches");
        if (!matches.isArray())
            throw new FeedException("provider error: " + root.path("message").asString("matches is not a list"));
        List<FeedGame> out = new ArrayList<>();
        for (JsonNode m : matches) {
            JsonNode id = m.path("id");
            if (!id.isIntegralNumber()) throw new FeedException("game without id: " + id);
            JsonNode h = m.path("homeTeam");
            JsonNode a = m.path("awayTeam");
            if (!h.path("id").isIntegralNumber() || !a.path("id").isIntegralNumber()) continue;
            Instant at;
            try {
                at = Instant.parse(m.path("utcDate").asString(""));
            } catch (DateTimeParseException e) {
                throw new FeedException("game " + id + " without utcDate");
            }
            String status = m.path("status").asString("");
            JsonNode full = m.path("score").path("fullTime");
            Integer home = score(full.path("home"));
            Integer away = score(full.path("away"));
            State state = switch (status) {
                case "SCHEDULED", "TIMED" -> State.NOT_STARTED;
                // SUSPENDED는 중단 뒤 이어서 한다
                case "IN_PLAY", "PAUSED", "LIVE", "SUSPENDED" -> State.LIVE;
                case "FINISHED" -> home != null && away != null ? State.FINAL : State.UNKNOWN;
                case "POSTPONED" -> State.POSTPONED;
                case "CANCELLED" -> State.CANCELED;
                default -> State.UNKNOWN;
            };
            out.add(new FeedGame(id.asString(), h.path("id").asString(), h.path("name").asString(""),
                    a.path("id").asString(), a.path("name").asString(""), at, state, status,
                    state == State.FINAL ? home : null, state == State.FINAL ? away : null));
        }
        return out;
    }

    private static Integer score(JsonNode n) {
        return n.isIntegralNumber() && n.asInt() >= 0 ? n.asInt() : null;
    }
}
