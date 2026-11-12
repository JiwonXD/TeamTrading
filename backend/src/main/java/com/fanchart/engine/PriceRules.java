package com.fanchart.engine;

import java.util.Optional;

/** KRX 호가가격단위를 단순화한 규칙과 전일 종가 기준 가격제한폭 */
public final class PriceRules {

    public static final double PRICE_LIMIT_RATE = 0.30;
    /** 동적 VI: 직전 체결가에서 이 비율(%) 이상 벗어난 가격에 체결될 차례면 단일가 매매로 넘어간다 */
    public static final int DYNAMIC_VI_PERCENT = 6;
    /** 정적 VI: 직전 단일가(그날 기준가 또는 마지막 단일가 체결가)에서 이 비율(%) 이상 */
    public static final int STATIC_VI_PERCENT = 10;
    /** 주문 한 건의 최대 수량. 가격×수량 계산이 long을 넘지 않게 막는다 */
    public static final long MAX_QUANTITY = 1_000_000;

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

    /** 기준 가격에서 percent% 미만으로 오른 가격 중 가장 높은 호가. 정수로 계산해 경계에서 어긋나지 않는다 */
    public static long viUpper(long reference, int percent) {
        long raw = (reference * (100 + percent) - 1) / 100;
        return raw / tickSize(raw) * tickSize(raw);
    }

    /** 기준 가격에서 percent% 미만으로 내린 가격 중 가장 낮은 호가 */
    public static long viLower(long reference, int percent) {
        long raw = reference * (100 - percent) / 100 + 1;
        long tick = tickSize(raw);
        return (raw + tick - 1) / tick * tick;
    }

    /** 주문이 규칙에 맞으면 빈 값, 아니면 사용자에게 보여줄 사유 */
    public static Optional<String> validate(OrderType type, long price, long quantity, long basePrice) {
        if (quantity <= 0) return Optional.of("수량은 1주 이상이어야 해요");
        if (quantity > MAX_QUANTITY) return Optional.of("한 번에 %,d주까지 주문할 수 있어요".formatted(MAX_QUANTITY));
        if (type == OrderType.MARKET) return Optional.empty();
        long upper = upperLimit(basePrice);
        long lower = lowerLimit(basePrice);
        if (price > upper) return Optional.of("상한가(%,d)보다 높게 주문할 수 없어요".formatted(upper));
        if (price < lower) return Optional.of("하한가(%,d)보다 낮게 주문할 수 없어요".formatted(lower));
        if (price % tickSize(price) != 0) return Optional.of("호가 단위(%d)에 맞지 않는 가격이에요".formatted(tickSize(price)));
        return Optional.empty();
    }
}
