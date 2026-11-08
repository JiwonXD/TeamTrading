package com.fanchart.news;

import com.fanchart.team.League;
import com.fanchart.team.Team;
import com.fanchart.team.TeamCatalog;
import com.fanchart.trading.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 구단 뉴스. 네이버 검색 API(뉴스)로 "구단 이름 + 종목"을 최신순으로 찾는다.
 * 구단별로 받은 결과를 cacheTtl 동안 두고 같이 쓴다. 다시 받다 실패하면 이전 결과를 그대로 보여준다
 */
@Service
@EnableConfigurationProperties(NewsProperties.class)
public class TeamNews {

    /** @param available 키가 있어 뉴스를 받을 수 있는지 */
    public record News(boolean available, List<Article> articles) {
    }

    /** @param source 언론사 주소(www 뺀 호스트) */
    public record Article(String title, String summary, String url, String source, Instant publishedAt) {
    }

    /** 뉴스를 받지 못했고 앞서 받아 둔 결과도 없다 */
    public static final class Unavailable extends RuntimeException {
        Unavailable(String message) {
            super(message);
        }
    }

    private record Cached(List<Article> articles, Instant at) {
    }

    private static final Logger log = LoggerFactory.getLogger(TeamNews.class);
    private static final int COUNT = 20;

    private final NewsProperties props;
    private final TeamCatalog teams;
    private final ObjectMapper json;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    TeamNews(NewsProperties props, TeamCatalog teams, ObjectMapper json, Clock clock) {
        this.props = props;
        this.teams = teams;
        this.json = json;
        this.clock = clock;
    }

    public News of(String teamId) {
        Team team = teams.find(teamId).orElseThrow(() -> new NotFoundException("없는 구단이에요"));
        if (!props.enabled()) return new News(false, List.of());
        Instant now = clock.instant();
        Cached hit = cache.get(teamId);
        if (hit != null && hit.at().plus(props.cacheTtl()).isAfter(now)) return new News(true, hit.articles());
        try {
            List<Article> articles = fetch(query(team));
            cache.put(teamId, new Cached(articles, now));
            return new News(true, articles);
        } catch (RuntimeException e) {
            if (hit == null) {
                log.warn("news for {} failed: {}", teamId, e.getMessage());
                throw e instanceof Unavailable u ? u : new Unavailable(e.getMessage());
            }
            log.warn("news for {} failed, showing earlier result: {}", teamId, e.getMessage());
            return new News(true, hit.articles());
        }
    }

    /** 이름만 찾으면 다른 뜻(T1, KT 등)이 섞이므로 종목 이름을 함께 넣는다 */
    static String query(Team team) {
        return team.name() + " " + sport(team.league());
    }

    private static String sport(League league) {
        return switch (league) {
            case KBO -> "야구";
            case LCK -> "롤";
            case NBA -> "농구";
            case K_LEAGUE, EPL, LA_LIGA, SERIE_A, BUNDESLIGA -> "축구";
        };
    }

    private List<Article> fetch(String query) {
        URI uri = URI.create(props.baseUrl() + "?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&display=" + COUNT + "&sort=date");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                .header("X-Naver-Client-Id", props.naverClientId())
                .header("X-Naver-Client-Secret", props.naverClientSecret()).GET().build();
        HttpResponse<String> res;
        try {
            res = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("news request interrupted");
        } catch (Exception e) {
            throw new Unavailable("news request failed: " + e);
        }
        if (res.statusCode() != 200) throw new Unavailable("news HTTP " + res.statusCode());
        return parse(json.readTree(res.body()));
    }

    static List<Article> parse(JsonNode body) {
        List<Article> out = new ArrayList<>();
        for (JsonNode item : body.path("items")) {
            String original = item.path("originallink").asString("");
            String link = original.isBlank() ? item.path("link").asString("") : original;
            // 주소나 시각이 이상한 기사 하나 때문에 목록 전체를 잃지 않게 그 기사만 건너뛴다
            try {
                if (!link.startsWith("http")) continue;
                out.add(new Article(text(item.path("title").asString("")), text(item.path("description").asString("")),
                        link, host(link), ZonedDateTime.parse(item.path("pubDate").asString(""),
                        DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()));
            } catch (RuntimeException e) {
                log.debug("skip news item {}: {}", link, e.getMessage());
            }
        }
        return out;
    }

    /** 검색어 강조(&lt;b&gt;)를 빼고 HTML 문자 참조를 글자로 돌린다 */
    private static String text(String html) {
        return HtmlUtils.htmlUnescape(html.replaceAll("<[^>]*>", "")).strip();
    }

    private static String host(String url) {
        String h = URI.create(url).getHost();
        return h == null ? "" : h.startsWith("www.") ? h.substring(4) : h;
    }
}
