package com.fanchart.feed;

import com.fanchart.feed.FeedGame.State;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * API-Sports 응답(API-Baseball v1 games, API-Football v3 fixtures)을 FeedGame으로 옮긴다.
 * <ul>
 * <li>응답이 HTTP 200이어도 errors가 비어 있지 않으면 쓰지 않는다(키·요금제·요청 한도 오류가 이렇게 온다).</li>
 * <li>시작 시각은 timestamp(유닉스 초)로 읽는다. 날짜 문자열의 시간대 해석에 기대지 않는다.</li>
 * <li>모르는 상태 코드, 점수가 없는 종료는 UNKNOWN으로 둔다. 동기화는 UNKNOWN에 아무것도 하지 않으므로
 *     제공처가 코드를 바꿔도 잘못된 결과로 배당하지 않는다.</li>
 * <li>몰수(AWD)·부전승(WO)은 점수가 경기 결과가 아니라서 UNKNOWN으로 두고 운영자가 판단한다.</li>
 * </ul>
 */
public final class ApiSportsParser {

    private ApiSportsParser() {
    }

    public static List<FeedGame> baseball(JsonNode root) {
        checkErrors(root);
        List<FeedGame> out = new ArrayList<>();
        for (JsonNode g : root.path("response")) {
            String status = g.path("status").path("short").asString("");
            Integer home = score(g.path("scores").path("home").path("total"));
            Integer away = score(g.path("scores").path("away").path("total"));
            State state = switch (status) {
                case "NS" -> State.NOT_STARTED;
                case "POST" -> State.POSTPONED;
                case "CANC", "ABD" -> State.CANCELED;
                case "FT" -> home != null && away != null ? State.FINAL : State.UNKNOWN;
                // IN1~IN9, 연장 회차, 중단(INTR, 다시 이어서 한다)
                default -> status.startsWith("IN") ? State.LIVE : State.UNKNOWN;
            };
            out.add(game(g.path("id"), g.path("teams"), g.path("timestamp"), state, status, home, away));
        }
        return out;
    }

    public static List<FeedGame> football(JsonNode root) {
        checkErrors(root);
        List<FeedGame> out = new ArrayList<>();
        for (JsonNode f : root.path("response")) {
            JsonNode fixture = f.path("fixture");
            String status = fixture.path("status").path("short").asString("");
            Integer home = score(f.path("goals").path("home"));
            Integer away = score(f.path("goals").path("away"));
            State state = switch (status) {
                case "TBD", "NS" -> State.NOT_STARTED;
                case "1H", "HT", "2H", "ET", "BT", "P", "LIVE", "SUSP", "INT" -> State.LIVE;
                case "FT", "AET", "PEN" -> home != null && away != null ? State.FINAL : State.UNKNOWN;
                case "PST" -> State.POSTPONED;
                case "CANC", "ABD" -> State.CANCELED;
                default -> State.UNKNOWN;
            };
            out.add(game(fixture.path("id"), f.path("teams"), fixture.path("timestamp"), state, status, home, away));
        }
        return out;
    }

    private static FeedGame game(JsonNode id, JsonNode teams, JsonNode timestamp, State state, String status,
                                 Integer home, Integer away) {
        if (!id.isIntegralNumber() || !timestamp.isIntegralNumber())
            throw new FeedException("game without id or timestamp: " + id);
        JsonNode h = teams.path("home");
        JsonNode a = teams.path("away");
        if (!h.path("id").isIntegralNumber() || !a.path("id").isIntegralNumber())
            throw new FeedException("game " + id + " without team ids");
        return new FeedGame(id.asString(), h.path("id").asString(), h.path("name").asString(""),
                a.path("id").asString(), a.path("name").asString(""), Instant.ofEpochSecond(timestamp.asLong()),
                state, status, state == State.FINAL ? home : null, state == State.FINAL ? away : null);
    }

    private static Integer score(JsonNode n) {
        return n.isIntegralNumber() && n.asInt() >= 0 ? n.asInt() : null;
    }

    /** errors는 비어 있으면 [] 또는 {}로 온다 */
    private static void checkErrors(JsonNode root) {
        JsonNode errors = root.path("errors");
        if (!errors.isMissingNode() && !errors.isNull() && !errors.isEmpty())
            throw new FeedException("provider error: " + errors);
        if (!root.path("response").isArray()) throw new FeedException("response is not a list");
    }
}
