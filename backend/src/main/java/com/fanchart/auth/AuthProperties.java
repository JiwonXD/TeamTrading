package com.fanchart.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * @param devLogin 닉네임만으로 로그인하는 개발·시연용 경로. demo 프로필에서만 켠다
 * @param admins   운영자 계정 id. 대사 결과처럼 계정 id가 보이는 화면을 볼 수 있다
 */
@ConfigurationProperties("fanchart.auth")
public record AuthProperties(boolean devLogin, List<String> admins) {

    public AuthProperties {
        admins = admins == null ? List.of() : List.copyOf(admins);
    }
}
