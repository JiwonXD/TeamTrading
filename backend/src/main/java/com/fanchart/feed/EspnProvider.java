package com.fanchart.feed;

import com.fanchart.feed.FeedGame.State;
import com.fanchart.feed.MatchFeedProperties.CompetitionFeed;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * ESPN 사이트 scoreboard({종목}/{리그 코드}/scoreboard?dates=YYYYMMDD). 대회 설정의 league-id는 "soccer/eng.fa"처럼
 * 종목까지 적는다. 키가 없고, 컵대회(FA컵, 카라바오컵, 코파 델 레이, 코파 이탈리아, DFB 포칼, 유로파, 컨퍼런스, ACL)의
 * 올해 시즌을 무료로 준다.
 * 공개 문서가 없는 ESPN 앱·웹용 주소라서 예고 없이 바뀔 수 있다(ADR 0029).
 * <ul>
 * <li>기간 조회(dates=시작-끝)는 막혀 있어서 하루씩 묻는다. ESPN의 하루는 미국 동부 날짜라서,
 *     한국 시간 하루를 덮도록 전날과 그날을 묻는다. 더 받은 경기는 동기화에 해가 없다.</li>
 * <li>일정 동기화는 어제부터 {@link #DAYS_AHEAD}일 뒤까지만 묻는다. 6시간마다 다시 돌고,
 *     새 경기는 시작 36시간 안이면 들이므로 그 사이에 빠지는 경기는 없다.</li>
 * <li>시작 시각은 초가 없는 UTC(2026-10-22T14:30Z)로 온다.</li>
 * <li>시작 시각이 지났는데 예정(pre)으로 남은 경기가 있다. 끝났다는 표시(completed)와 점수가 함께 와야만 FINAL로 본다.</li>
 * <li>ESPN의 팀·경기 번호는 종목마다 따로 매겨 겹칠 수 있다(축구 13번 팀과 NBA 13번 레이커스). 같은 출처(espn)로
 *     구단을 연결하므로 축구가 아닌 종목은 번호 앞에 종목을 붙인다(basketball:13).</li>
 * <li>승부차기로 끝난 경기는 점수 칸이 승부차기를 담는지 확인하지 못해 UNKNOWN으로 두고 운영자가 넣는다.
 *     몰수·중도 종료도 점수가 경기 결과가 아니라서 UNKNOWN이다.</li>
 * </ul>
 */
final class EspnProvider implements FeedProvider {

    static final int DAYS_AHEAD = 7;
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter NO_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmXXX");

    @Override
    public URI defaultBaseUrl() {
        return URI.create("https://site.api.espn.com");
    }

    /** 키가 없는 공개 주소라 우리 쪽에서 스스로 정한 상한(대회 9개 × 하루 4번 × 9일 ≈ 330회) */
    @Override
    public int defaultDailyLimit() {
        return 1500;
    }

    @Override
    public boolean needsKey() {
        return false;
    }

    @Override
    public HttpRequest request(CompetitionFeed feed, String key, URI base, LocalDate date, LocalDate today) {
        return requests(feed, key, base, date, today).getFirst();
    }

    @Override
    public List<HttpRequest> requests(CompetitionFeed feed, String key, URI base, LocalDate date, LocalDate today) {
        LocalDate from = date != null ? date.minusDays(1) : today.minusDays(1);
        LocalDate to = date != null ? date : today.plusDays(DAYS_AHEAD);
        List<HttpRequest> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            URI uri = URI.create(base + "/apis/site/v2/sports/" + feed.leagueId() + "/scoreboard?dates="
                    + d.format(DAY));
            out.add(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build());
        }
        return out;
    }

    @Override
    public List<FeedGame> parse(CompetitionFeed feed, JsonNode root) {
        String sport = feed.leagueId().substring(0, Math.max(0, feed.leagueId().indexOf('/')));
        List<FeedGame> games = parse(root);
        if (sport.isEmpty() || sport.equals("soccer")) return games;
        String p = sport + ":";
        return games.stream().map(g -> new FeedGame(p + g.ref(), p + g.homeTeamId(), g.homeName(), p + g.awayTeamId(),
                g.awayName(), g.scheduledAt(), g.state(), g.rawStatus(), g.homeScore(), g.awayScore())).toList();
    }

    @Override
    public List<FeedGame> parse(JsonNode root) {
        JsonNode events = root.path("events");
        if (!events.isArray()) throw new FeedException("provider error: events is not a list");
        List<FeedGame> out = new ArrayList<>();
        for (JsonNode e : events) {
            String id = e.path("id").asString("");
            if (id.isBlank()) throw new FeedException("game without id");
            JsonNode h = null;
            JsonNode a = null;
            for (JsonNode c : e.path("competitions").path(0).path("competitors")) {
                String side = c.path("homeAway").asString("");
                if (side.equals("home")) h = c;
                else if (side.equals("away")) a = c;
            }
            // 대진이 정해지지 않은 경기는 건너뛴다
            if (h == null || a == null || h.path("team").path("id").asString("").isBlank()
                    || a.path("team").path("id").asString("").isBlank()) continue;
            Instant at = instant(id, e.path("date").asString(""));
            JsonNode type = e.path("status").path("type");
            String name = type.path("name").asString("");
            String phase = type.path("state").asString("");
            Integer home = score(h.path("score"));
            Integer away = score(a.path("score"));
            State state = switch (name) {
                case "STATUS_POSTPONED" -> State.POSTPONED;
                case "STATUS_CANCELED", "STATUS_CANCELLED" -> State.CANCELED;
                case "STATUS_FINAL_PEN", "STATUS_ABANDONED", "STATUS_FORFEIT" -> State.UNKNOWN;
                // 중단(SUSPENDED)은 뒤에 이어서 한다
                case "STATUS_SUSPENDED" -> State.LIVE;
                default -> switch (phase) {
                    case "pre" -> State.NOT_STARTED;
                    case "in" -> State.LIVE;
                    case "post" -> type.path("completed").asBoolean(false) && home != null && away != null
                            ? State.FINAL : State.UNKNOWN;
                    default -> State.UNKNOWN;
                };
            };
            out.add(new FeedGame(id, h.path("team").path("id").asString(), h.path("team").path("displayName").asString(""),
                    a.path("team").path("id").asString(), a.path("team").path("displayName").asString(""), at, state,
                    name, state == State.FINAL ? home : null, state == State.FINAL ? away : null));
        }
        return out;
    }

    private static Instant instant(String id, String text) {
        try {
            return OffsetDateTime.parse(text, NO_SECONDS).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(text);
            } catch (DateTimeParseException e2) {
                throw new FeedException("game " + id + " with invalid date: " + text);
            }
        }
    }

    /** 점수는 문자열("2")로 온다 */
    private static Integer score(JsonNode n) {
        String s = n.isNumber() ? String.valueOf(n.asInt()) : n.asString("");
        if (!s.matches("\\d{1,3}")) return null;
        return Integer.parseInt(s);
    }
}
