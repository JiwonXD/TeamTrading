package com.fanchart.trading;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 매일 점검시간. 이 시간에는 매수·매도 주문을 받지 않는다(ADR 0036).
 * 서버 컨테이너의 시간대와 상관없이 한국 시간으로 계산한다.
 */
public final class MarketHours {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    public static final LocalTime MAINTENANCE_START = LocalTime.MIDNIGHT;
    public static final LocalTime MAINTENANCE_END = LocalTime.of(0, 30);

    private MarketHours() {
    }

    /** 그 시각이 점검시간(시작 포함, 끝 미포함)인지 */
    public static boolean isMaintenance(Instant at) {
        LocalTime t = at.atZone(ZONE).toLocalTime();
        return !t.isBefore(MAINTENANCE_START) && t.isBefore(MAINTENANCE_END);
    }
}
