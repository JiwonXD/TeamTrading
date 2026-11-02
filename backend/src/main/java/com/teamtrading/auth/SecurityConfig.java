package com.teamtrading.auth;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

import java.util.Map;

/**
 * 세션 쿠키 로그인. 쿠키를 쓰므로 상태를 바꾸는 요청에는 CSRF 토큰(XSRF-TOKEN 쿠키 → X-XSRF-TOKEN 헤더)이 필요하다.
 * 시세·호가·랭킹·감시 기록·실시간 시세는 로그인 없이 볼 수 있다.
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
class SecurityConfig {

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SecurityFilterChain api(HttpSecurity http, SecurityContextRepository contexts,
                            ObjectProvider<ClientRegistrationRepository> registrations,
                            ObjectProvider<AccountLogin> login) throws Exception {
        http.csrf(c -> c.spa())
                .securityContext(c -> c.securityContextRepository(contexts))
                .anonymous(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/surveillance/reconciliation").hasRole("ADMIN")
                        .requestMatchers("/api/accounts/me/**", "/api/orders/**", "/api/auth/logout").authenticated()
                        .anyRequest().permitAll());
        if (registrations.getIfAvailable() != null) {
            http.oauth2Login(o -> o.successHandler((req, res, auth) -> {
                OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) auth;
                String provider = token.getAuthorizedClientRegistrationId();
                AccountLogin accounts = login.getObject();
                accounts.signIn(accounts.findOrCreate(provider, subject(provider, token.getPrincipal().getAttributes())),
                        req, res);
                res.sendRedirect("/");
            }).failureHandler((req, res, e) -> res.sendRedirect("/#login-failed")));
        }
        return http.build();
    }

    /** 제공자의 사용자 고유 id. 네이버는 응답이 response 안에 한 번 더 싸여 있다 */
    static String subject(String provider, Map<String, Object> attributes) {
        Object id = switch (provider) {
            case "naver" -> attributes.get("response") instanceof Map<?, ?> r ? r.get("id") : null;
            case "google" -> attributes.get("sub");
            default -> attributes.get("id");
        };
        if (id == null) throw new IllegalStateException("no user id from " + provider);
        return id.toString();
    }
}
