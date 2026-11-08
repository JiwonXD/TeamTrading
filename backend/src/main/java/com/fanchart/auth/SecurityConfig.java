package com.fanchart.auth;


import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

import java.time.Clock;

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
                            ObjectProvider<AccountLogin> login, Withdrawal withdrawal, Clock clock) throws Exception {
        http.csrf(c -> c.spa())
                .securityContext(c -> c.securityContextRepository(contexts))
                .anonymous(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(new WithdrawnSessionFilter(withdrawal), AuthorizationFilter.class)
                .authorizeHttpRequests(a -> a
                        // 운영 지표는 외부에 공개하지 않는 관리 포트(8081)에서만 열린다
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        .requestMatchers("/api/surveillance/**", "/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/accounts/me/**", "/api/orders/**", "/api/auth/logout",
                                "/api/offerings/*/subscriptions/**", "/api/posts/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/teams/*/posts").authenticated()
                        .anyRequest().permitAll());
        if (registrations.getIfAvailable() != null) {
            OAuthLoginSuccess handler = new OAuthLoginSuccess(login.getObject(), withdrawal, clock);
            http.oauth2Login(o -> o.successHandler(handler).failureHandler(handler));
        }
        return http.build();
    }
}
