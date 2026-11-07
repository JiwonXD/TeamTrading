package com.teamtrading.feed;

import com.teamtrading.feed.MatchFeedProperties.LeagueFeed;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * API-Sports에서 리그의 경기를 받는다. 시즌 전체(date 없이) 또는 하루치(date, 한국 시간)를 묻는다.
 * 요청을 보내기 전에 제공처별 하루 상한을 센다. 실패한 요청도 제공처는 세므로 함께 센다.
 */
@Component
public class ApiSportsClient {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final MatchFeedProperties props;
    private final ObjectMapper json;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, Integer> used = new HashMap<>();
    private LocalDate usedOn;

    ApiSportsClient(MatchFeedProperties props, ObjectMapper json, Clock clock) {
        this.props = props;
        this.json = json;
        this.clock = clock;
    }

    public List<FeedGame> games(LeagueFeed feed, LocalDate date) {
        LocalDate today = clock.instant().atZone(KST).toLocalDate();
        int season = feed.season() != null ? feed.season() : (date != null ? date : today).getYear();
        boolean baseball = MatchFeedProperties.BASEBALL.equals(feed.source());
        String query = "league=" + feed.leagueId() + "&season=" + season + "&timezone=Asia/Seoul"
                + (date != null ? "&date=" + date : "");
        URI uri = URI.create(feed.baseUrl() + (baseball ? "/games?" : "/fixtures?") + query);

        spend(feed.source(), today);
        JsonNode body;
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                    .header("x-apisports-key", props.apiKey()).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) throw new FeedException(feed.source() + " HTTP " + res.statusCode());
            body = json.readTree(res.body());
        } catch (IOException e) {
            throw new FeedException(feed.source() + " request failed: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FeedException(feed.source() + " request interrupted");
        } catch (tools.jackson.core.JacksonException e) {
            throw new FeedException(feed.source() + " sent invalid JSON");
        }
        return baseball ? ApiSportsParser.baseball(body) : ApiSportsParser.football(body);
    }

    /** 오늘 이 제공처에 남은 요청 수 */
    public synchronized int remaining(String source) {
        LocalDate today = clock.instant().atZone(KST).toLocalDate();
        return props.dailyRequestLimit() - (today.equals(usedOn) ? used.getOrDefault(source, 0) : 0);
    }

    private synchronized void spend(String source, LocalDate today) {
        if (!today.equals(usedOn)) {
            used.clear();
            usedOn = today;
        }
        int n = used.getOrDefault(source, 0);
        if (n >= props.dailyRequestLimit()) throw new FeedException(source + " daily request limit reached");
        used.put(source, n + 1);
    }
}
