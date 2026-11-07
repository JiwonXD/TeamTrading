package com.fanchart.engine;

import java.util.List;

/**
 * @param resting           체결 후 호가창에 남은 지정가 주문 (없으면 null)
 * @param canceledQuantity  시장가 잔량 또는 자기매매 방지로 취소된 수량
 * @param interrupted       VI 범위 밖 가격에 체결될 뻔해 연속 매매를 멈추고 단일가 매매로 넘어갔는지
 */
public record MatchResult(List<Trade> trades, Order resting, long canceledQuantity, boolean selfTradePrevented,
                          boolean interrupted) {

    public long filledQuantity() {
        return trades.stream().mapToLong(Trade::quantity).sum();
    }
}
