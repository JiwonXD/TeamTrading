package com.fanchart.trading;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Optional;

/**
 * 차트 봉 단위. 경계는 한국 시간 기준이다. 분 단위는 그날 자정부터 N분씩 끊고,
 * 주는 월요일, 월은 1일, 년은 1월 1일에 시작한다.
 */
public enum CandleUnit {
    M1("1m", 1), M3("3m", 3), M5("5m", 5), M10("10m", 10), M15("15m", 15), M30("30m", 30),
    M60("60m", 60), M120("120m", 120), M240("240m", 240),
    DAY("day", 0), WEEK("week", 0), MONTH("month", 0), YEAR("year", 0);

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public final String code;
    private final int minutes;

    CandleUnit(String code, int minutes) {
        this.code = code;
        this.minutes = minutes;
    }

    public static Optional<CandleUnit> of(String code) {
        return Arrays.stream(values()).filter(u -> u.code.equals(code)).findFirst();
    }

    /** t가 속한 봉의 시작 시각 */
    public Instant start(Instant t) {
        ZonedDateTime z = t.atZone(KST);
        LocalDate d = z.toLocalDate();
        return switch (this) {
            case DAY -> d.atStartOfDay(KST).toInstant();
            case WEEK -> d.minusDays(d.getDayOfWeek().getValue() - 1).atStartOfDay(KST).toInstant();
            case MONTH -> d.withDayOfMonth(1).atStartOfDay(KST).toInstant();
            case YEAR -> d.withDayOfYear(1).atStartOfDay(KST).toInstant();
            default -> {
                int m = z.getHour() * 60 + z.getMinute();
                yield d.atStartOfDay(KST).plusMinutes(m - m % minutes).toInstant();
            }
        };
    }
}
