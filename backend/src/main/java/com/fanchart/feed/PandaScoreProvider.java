package com.fanchart.feed;

import com.fanchart.feed.FeedGame.State;
import com.fanchart.feed.MatchFeedProperties.LeagueFeed;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * PandaScore lol/matches(리그 오브 레전드). 무료 요금제로 일정·결과를 받는다(시간당 1,000회).
 * <ul>
 * <li>시즌 전체 대신 이틀 전부터 45일 뒤까지의 경기를 예정 시각 순으로 한 쪽(100경기)만 받는다.
 *     새 경기는 시작 36시간 안의 것만 들이므로 지난 경기는 필요 없고, 잘려도 먼 경기부터 빠진다.</li>
 * <li>홈·원정은 opponents의 첫째·둘째 팀으로 정한다. 점수는 세트 승수(results)다.</li>
 * <li>상대가 정해지지 않았거나 예정 시각이 없는 경기는 건너뛴다(플레이오프 대진 미정).</li>
 * <li>몰수(forfeit)로 끝난 경기는 세트 승수가 경기 결과가 아니라서 UNKNOWN으로 두고 운영자가 판단한다.</li>
 * </ul>
 */
final class PandaScoreProvider implements FeedProvider {

    static final int DAYS_BACK = 2;
    static final int DAYS_AHEAD = 45;

    @Override
    public URI defaultBaseUrl() {
        return URI.create("https://api.pandascore.co");
    }

    @Override
    public int defaultDailyLimit() {
        return 500;
    }

    @Override
    public HttpRequest request(LeagueFeed feed, URI base, LocalDate date, LocalDate today) {
        Instant from = (date != null ? date : today.minusDays(DAYS_BACK)).atStartOfDay(FeedClient.KST).toInstant();
        Instant to = (date != null ? date.plusDays(1) : today.plusDays(DAYS_AHEAD)).atStartOfDay(FeedClient.KST).toInstant();
        String query = q("filter[league_id]", feed.leagueId()) + "&" + q("range[scheduled_at]", from + "," + to)
                + "&sort=scheduled_at&" + q("page[size]", "100");
        return HttpRequest.newBuilder(URI.create(base + "/lol/matches?" + query)).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + feed.apiKey()).GET().build();
    }

    private static String q(String key, String value) {
        return URLEncoder.encode(key, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Override
    public List<FeedGame> parse(JsonNode root) {
        if (!root.isArray()) throw new FeedException("provider error: " + root.path("error").asString(
                root.path("message").asString("response is not a list")));
        List<FeedGame> out = new ArrayList<>();
        for (JsonNode m : root) {
            JsonNode id = m.path("id");
            if (!id.isIntegralNumber()) throw new FeedException("game without id: " + id);
            JsonNode opponents = m.path("opponents");
            if (opponents.size() != 2) continue;
            JsonNode h = opponents.get(0).path("opponent");
            JsonNode a = opponents.get(1).path("opponent");
            if (!h.path("id").isIntegralNumber() || !a.path("id").isIntegralNumber()) continue;
            String scheduled = m.path("scheduled_at").asString("");
            if (scheduled.isEmpty()) continue;
            Instant at;
            try {
                at = Instant.parse(scheduled);
            } catch (DateTimeParseException e) {
                throw new FeedException("game " + id + " with invalid scheduled_at: " + scheduled);
            }
            String status = m.path("status").asString("");
            Integer home = wins(m.path("results"), h.path("id").asLong());
            Integer away = wins(m.path("results"), a.path("id").asLong());
            State state = switch (status) {
                case "not_started" -> State.NOT_STARTED;
                case "running" -> State.LIVE;
                case "finished" -> home != null && away != null && !m.path("forfeit").asBoolean(false)
                        ? State.FINAL : State.UNKNOWN;
                case "postponed" -> State.POSTPONED;
                case "canceled" -> State.CANCELED;
                default -> State.UNKNOWN;
            };
            out.add(new FeedGame(id.asString(), h.path("id").asString(), h.path("name").asString(""),
                    a.path("id").asString(), a.path("name").asString(""), at, state, status,
                    state == State.FINAL ? home : null, state == State.FINAL ? away : null));
        }
        return out;
    }

    private static Integer wins(JsonNode results, long teamId) {
        for (JsonNode r : results) {
            if (r.path("team_id").isIntegralNumber() && r.path("team_id").asLong() == teamId) {
                JsonNode s = r.path("score");
                return s.isIntegralNumber() && s.asInt() >= 0 ? s.asInt() : null;
            }
        }
        return null;
    }
}
