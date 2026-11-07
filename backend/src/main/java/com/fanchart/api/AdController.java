package com.fanchart.api;

import com.fanchart.ad.AdMobVerifier;
import com.fanchart.ad.AdMobVerifier.InvalidCallbackException;
import com.fanchart.ad.AdProperties;
import com.fanchart.ad.AdRewardService;
import com.fanchart.ad.AdSession;
import com.fanchart.trading.NotFoundException;
import com.fanchart.trading.TradingException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;

/**
 * 광고 시청 보상. 세션 열기·조회는 로그인, AdMob 콜백은 구글 서명으로만 인증한다.
 * 시연 서버(fanchart.ads.demo)는 광고 네트워크 없이 15초를 기다리면 지급하는 가짜 광고를 연다.
 */
@RestController
class AdController {

    static final Duration DEMO_WATCH = Duration.ofSeconds(15);
    private static final Logger log = LoggerFactory.getLogger(AdController.class);

    /** @param demo 시연용 가짜 광고를 쓸 수 있는지. 아니면 앱의 광고 SDK로만 받는다 */
    record StatusResponse(long amount, int dailyLimit, int paidToday, boolean demo) {
    }

    record SessionResponse(String id, AdSession.Status status) {
        static SessionResponse of(AdSession s) {
            return new SessionResponse(s.id(), s.status());
        }
    }

    private final AdRewardService ads;
    private final AdMobVerifier admob;
    private final AdProperties props;
    private final Clock clock;

    AdController(AdRewardService ads, AdMobVerifier admob, AdProperties props, Clock clock) {
        this.ads = ads;
        this.admob = admob;
        this.props = props;
        this.clock = clock;
    }

    @GetMapping("/api/accounts/me/ads")
    StatusResponse status(@AuthenticationPrincipal String accountId) {
        var s = ads.status(accountId);
        return new StatusResponse(s.amount(), s.dailyLimit(), s.paidToday(), props.demo());
    }

    @PostMapping("/api/accounts/me/ads")
    @ResponseStatus(HttpStatus.CREATED)
    SessionResponse open(@AuthenticationPrincipal String accountId) {
        return SessionResponse.of(ads.open(accountId));
    }

    @GetMapping("/api/accounts/me/ads/{sessionId}")
    SessionResponse session(@AuthenticationPrincipal String accountId, @PathVariable String sessionId) {
        return SessionResponse.of(ads.find(accountId, sessionId));
    }

    /** 시연용 가짜 광고를 끝까지 봤다. 시연 서버에서만 열린다 */
    @PostMapping("/api/accounts/me/ads/{sessionId}/demo-complete")
    SessionResponse demoComplete(@AuthenticationPrincipal String accountId, @PathVariable String sessionId) {
        if (!props.demo()) throw new NotFoundException("시연 서버가 아니에요");
        AdSession s = ads.find(accountId, sessionId);
        if (s.createdAt().plus(DEMO_WATCH).isAfter(clock.instant()))
            throw new TradingException("광고를 끝까지 봐 주세요");
        return SessionResponse.of(ads.complete(sessionId, "demo:" + sessionId));
    }

    /**
     * AdMob 서버 측 검증 콜백. 서명이 맞고 등록한 광고 단위일 때만 지급한다.
     * 200이 아니면 AdMob이 다시 보내므로, 이미 처리한 같은 거래는 200으로 답한다.
     */
    @GetMapping("/api/ads/admob/callback")
    ResponseEntity<Void> admobCallback(HttpServletRequest request) {
        AdMobVerifier.Callback cb;
        try {
            cb = admob.verify(request.getQueryString());
        } catch (InvalidCallbackException e) {
            log.warn("rejected AdMob callback: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (!props.admobAdUnits().contains(cb.adUnit())) {
            log.warn("rejected AdMob callback for unregistered ad unit {}", cb.adUnit());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        ads.complete(cb.sessionId(), "admob:" + cb.transactionId());
        return ResponseEntity.ok().build();
    }
}
