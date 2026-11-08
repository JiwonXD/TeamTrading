package com.fanchart.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 비밀번호 재설정 메일을 보낼 SMTP 서버. host를 비우면 메일을 보내지 않고 화면에 '비밀번호 찾기'를 보이지 않는다.
 * spring.mail.*을 쓰지 않는 까닭: 값이 빈 문자열이어도 Spring Boot가 메일 발송기를 만들어 버린다.
 *
 * @param siteUrl 메일 속 링크의 사이트 주소. 요청의 Host 헤더로 만들면 남이 링크를 자기 사이트로 바꿔 보내게 할 수 있어 설정으로 고정한다
 */
@ConfigurationProperties("fanchart.mail")
public record ResetMailProperties(String host, int port, String username, String password, String from, String siteUrl) {

    public ResetMailProperties {
        if (port == 0) port = 587;
        if (siteUrl == null || siteUrl.isBlank()) siteUrl = "https://fanchart.kr";
    }
}
