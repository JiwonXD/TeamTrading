package com.fanchart.feed;

import com.fanchart.feed.MatchFeedProperties.CompetitionFeed;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.LocalDate;
import java.util.List;

/**
 * 제공처 하나의 요청 모양과 응답 해석. 응답을 제공처와 무관한 FeedGame으로 옮기므로
 * 동기화 규칙(MatchFeedSync)은 제공처를 바꿔도 그대로다. 새 제공처는 이것 하나만 더한다.
 */
interface FeedProvider {

    URI defaultBaseUrl();

    /** 같은 키로 하루(한국 시간)에 보낼 요청 상한. 제공처 무료 한도보다 낮게 둔다 */
    int defaultDailyLimit();

    /**
     * @param key   제공처 키
     * @param date  null이면 시즌(또는 가까운 일정) 전체, 있으면 그날(한국 시간) 경기
     * @param today 오늘(한국 시간)
     */
    HttpRequest request(CompetitionFeed feed, String key, URI base, LocalDate date, LocalDate today);

    /** 한 번 받을 때 보낼 요청들. 기간 조회가 없는 제공처는 하루씩 여러 번 묻는다. 기본은 {@link #request} 하나 */
    default List<HttpRequest> requests(CompetitionFeed feed, String key, URI base, LocalDate date, LocalDate today) {
        return List.of(request(feed, key, base, date, today));
    }

    /** 키가 필요한 제공처인지. 키가 없는 공개 주소면 false */
    default boolean needsKey() {
        return true;
    }

    /** 쓸 수 없는 응답이면 FeedException */
    List<FeedGame> parse(JsonNode body);
}
