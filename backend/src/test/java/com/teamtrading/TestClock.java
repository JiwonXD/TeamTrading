package com.teamtrading;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** 테스트에서 시각을 직접 옮기는 시계. {@code @Import(TestClock.Config.class)}로 앱 전체 시계를 바꾼다 */
public class TestClock extends Clock {

    private volatile Instant now;

    public TestClock(Instant start) {
        this.now = start;
    }

    public void set(Instant instant) {
        now = instant;
    }

    public void advance(Duration d) {
        now = now.plus(d);
    }

    @Override
    public ZoneId getZone() {
        return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }

    @TestConfiguration
    public static class Config {
        /** 2026-11-02 23:30 KST */
        public static final Instant START = Instant.parse("2026-11-02T14:30:00Z");

        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock(START);
        }
    }
}
