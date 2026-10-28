package com.teamtrading.engine;

import java.util.List;

/**
 * @param resting           체결 후 호가창에 남은 지정가 주문 (없으면 null)
 * @param canceledQuantity  시장가 잔량 또는 자기매매 방지로 취소된 수량
 */
public record MatchResult(List<Trade> trades, Order resting, long canceledQuantity, boolean selfTradePrevented) {

    public long filledQuantity() {
        return trades.stream().mapToLong(Trade::quantity).sum();
    }
}
