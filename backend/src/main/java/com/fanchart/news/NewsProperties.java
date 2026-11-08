package com.fanchart.news;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * 구단 뉴스(네이버 검색 API 뉴스). 키가 없으면 뉴스를 받지 않고 화면에 준비 중으로 보인다.
 *
 * @param naverClientId     네이버 개발자센터 애플리케이션의 Client ID
 * @param naverClientSecret 같은 애플리케이션의 Client Secret
 * @param cacheTtl          구단별로 받아 둔 기사를 다시 받기까지의 시간. 하루 호출 한도(25,000회)를 넘지 않게 한다
 * @param baseUrl           검색 API 주소(테스트에서 바꾼다)
 */
@ConfigurationProperties("fanchart.news")
public record NewsProperties(String naverClientId, String naverClientSecret, Duration cacheTtl, URI baseUrl) {

    public NewsProperties {
        if (naverClientId == null) naverClientId = "";
        if (naverClientSecret == null) naverClientSecret = "";
        if (cacheTtl == null) cacheTtl = Duration.ofMinutes(15);
        if (baseUrl == null) baseUrl = URI.create("https://openapi.naver.com/v1/search/news.json");
    }

    public boolean enabled() {
        return !naverClientId.isBlank() && !naverClientSecret.isBlank();
    }
}
