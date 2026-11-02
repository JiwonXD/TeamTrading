package com.teamtrading.ad;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.List;

/**
 * @param admobAdUnits  보상을 줄 AdMob 광고 단위 id. 비어 있으면 AdMob 콜백을 모두 거절한다.
 *                      다른 앱이 우리 콜백 주소를 써서 보낸 콜백도 구글 서명은 맞으므로 광고 단위로 걸러야 한다
 * @param admobKeysUrl  AdMob 서명 검증용 공개키 목록
 * @param demo          시연용 가짜 광고. 광고 네트워크 없이 정해진 시간을 기다리면 지급한다. 운영에서는 켜지 않는다
 */
@ConfigurationProperties("teamtrading.ads")
public record AdProperties(List<String> admobAdUnits, URI admobKeysUrl, Boolean demo) {

    public AdProperties {
        admobAdUnits = admobAdUnits == null ? List.of() : List.copyOf(admobAdUnits);
        if (admobKeysUrl == null) admobKeysUrl = URI.create("https://www.gstatic.com/admob/reward/verifier-keys.json");
        if (demo == null) demo = false;
    }
}
