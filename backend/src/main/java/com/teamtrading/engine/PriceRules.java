package com.teamtrading.engine;

import java.util.Optional;

/** KRX 호가가격단위를 단순화한 규칙과 전일 종가 기준 가격제한폭 */
public final class PriceRules {

    public static final double PRICE_LIMIT_RATE = 0.30;

    private PriceRules() {
    }

    public static long tickSize(long price) {
        if (price < 2_000) return 1;
        if (price < 5_000) return 5;
        if (price < 20_000) return 10;
        if (price < 50_000) return 50;
        return 100;
    }

    public static long upperLimit(long basePrice) {
        long raw = (long) Math.floor(basePrice * (1 + PRICE_LIMIT_RATE));
        long tick = tickSize(raw);
        return raw / tick * tick;
    }

    public static long lowerLimit(long basePrice) {
        long raw = (long) Math.ceil(basePrice * (1 - PRICE_LIMIT_RATE));
        long tick = tickSize(raw);
        return (raw + tick - 1) / tick * tick;
    }

    /** 주문이 규칙에 맞으면 빈 값, 아니면 사용자에게 보여줄 사유 */
    public static Optional<String> validate(OrderType type, long price, long quantity, long basePrice) {
        if (quantity <= 0) return Optional.of("수량은 1주 이상이어야 해요");
        if (type == OrderType.MARKET) return Optional.empty();
        long upper = upperLimit(basePrice);
        long lower = lowerLimit(basePrice);
        if (price > upper) return Optional.of("상한가(%,d)보다 높게 주문할 수 없어요".formatted(upper));
        if (price < lower) return Optional.of("하한가(%,d)보다 낮게 주문할 수 없어요".formatted(lower));
        if (price % tickSize(price) != 0) return Optional.of("호가 단위(%d)에 맞지 않는 가격이에요".formatted(tickSize(price)));
        return Optional.empty();
    }
}
