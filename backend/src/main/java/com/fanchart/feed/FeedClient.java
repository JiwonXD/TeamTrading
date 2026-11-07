package com.fanchart.feed;

import com.fanchart.feed.MatchFeedProperties.CompetitionFeed;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

/**
 * 대회 설정의 제공처로 경기를 받는다. 요청을 보내기 전에 제공처·키별 하루 상한을 센다.
 * 실패한 요청도 제공처는 세므로 함께 센다. 같은 키로 여러 대회를 부르므로 상한도 함께 쓴다.
 */
@Component
public class FeedClient {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    static final Map<String, FeedProvider> PROVIDERS = Map.of(
            MatchFeedProperties.BASEBALL, ApiSportsProvider.BASEBALL,
            MatchFeedProperties.FOOTBALL, ApiSportsProvider.FOOTBALL,
            MatchFeedProperties.FOOTBALL_DATA, new FootballDataProvider(),
            MatchFeedProperties.PANDASCORE, new PandaScoreProvider());

    private final MatchFeedProperties props;
    private final ObjectMapper json;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, Integer> used = new HashMap<>();
    private LocalDate usedOn;

    FeedClient(MatchFeedProperties props, ObjectMapper json, Clock clock) {
        this.props = props;
        this.json = json;
        this.clock = clock;
    }

    /** @param date null이면 시즌 전체, 있으면 그날(한국 시간) 경기 */
    public List<FeedGame> games(CompetitionFeed feed, LocalDate date) {
        String key = props.keyFor(feed.source());
        if (key.isBlank()) throw new FeedException(feed.source() + " has no API key");
        FeedProvider provider = PROVIDERS.get(feed.source());
        LocalDate today = clock.instant().atZone(KST).toLocalDate();
        URI base = feed.baseUrl() != null ? feed.baseUrl() : provider.defaultBaseUrl();

        spend(feed, key, provider, today);
        JsonNode body;
        try {
            HttpResponse<String> res = http.send(provider.request(feed, key, base, date, today),
                    HttpResponse.BodyHandlers.ofString());
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
        return provider.parse(body);
    }

    /** 오늘 이 대회의 제공처·키로 남은 요청 수 */
    public synchronized int remaining(CompetitionFeed feed) {
        LocalDate today = clock.instant().atZone(KST).toLocalDate();
        String budget = budgetKey(feed.source(), props.keyFor(feed.source()));
        return limit(feed, PROVIDERS.get(feed.source())) - (today.equals(usedOn) ? used.getOrDefault(budget, 0) : 0);
    }

    private synchronized void spend(CompetitionFeed feed, String key, FeedProvider provider, LocalDate today) {
        if (!today.equals(usedOn)) {
            used.clear();
            usedOn = today;
        }
        String budget = budgetKey(feed.source(), key);
        int n = used.getOrDefault(budget, 0);
        if (n >= limit(feed, provider)) throw new FeedException(feed.source() + " daily request limit reached");
        used.put(budget, n + 1);
    }

    private static int limit(CompetitionFeed feed, FeedProvider provider) {
        return feed.dailyRequestLimit() != null ? feed.dailyRequestLimit() : provider.defaultDailyLimit();
    }

    /** 제공처 한도는 제공처(API)와 키마다 센다. 같은 키를 쓰는 대회끼리 한도를 함께 쓴다 */
    private static String budgetKey(String source, String key) {
        return source + "\n" + key;
    }
}
