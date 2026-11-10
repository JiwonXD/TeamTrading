package com.fanchart.auth;

import com.fanchart.trading.TradingException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 아이디 가입·로그인 요청을 접속 IP마다 일정 시간에 정해진 횟수까지만 받는다.
 * 봇의 대량 가입(가입 보상 받기)과 여러 계정에 비밀번호를 하나씩 넣어 보는 공격을 막는다. 계정별 5회 잠금과는 따로 센다.
 * 서버 한 대 메모리에 세므로 재시작하면 처음부터 센다.
 */
@Component
class AuthRateLimit {

    enum Action {
        SIGNUP(10, Duration.ofHours(1), "가입 요청이 너무 많아요. 1시간 뒤에 다시 시도해 주세요"),
        LOGIN(20, Duration.ofMinutes(10), "로그인 시도가 너무 많아요. 10분 뒤에 다시 시도해 주세요");

        final int max;
        final Duration window;
        final String message;

        Action(int max, Duration window, String message) {
            this.max = max;
            this.window = window;
            this.message = message;
        }
    }

    private record Window(Instant start, Duration length, int count) {
        boolean expired(Instant now) {
            return !now.isBefore(start.plus(length));
        }
    }

    /** 오래된 기록을 정리하기 시작하는 기록 수 */
    private static final int SWEEP_SIZE = 10_000;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    AuthRateLimit(Clock clock) {
        this.clock = clock;
    }

    /** 이번 요청을 세고, 한도를 넘었으면 거절한다 */
    void check(Action action, String ip) {
        Instant now = clock.instant();
        if (windows.size() > SWEEP_SIZE) windows.values().removeIf(w -> w.expired(now));
        Window w = windows.compute(action + ":" + ip, (k, old) -> old == null || old.expired(now)
                ? new Window(now, action.window, 1)
                : new Window(old.start(), old.length(), old.count() + 1));
        if (w.count() > action.max) throw new TradingException(action.message);
    }
}
