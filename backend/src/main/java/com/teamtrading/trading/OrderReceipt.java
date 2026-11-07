package com.teamtrading.trading;

import com.teamtrading.engine.OrderType;

public record OrderReceipt(long orderId, long filledQuantity, long restingQuantity, long canceledQuantity,
                           boolean selfTradePrevented, String message) {

    static OrderReceipt of(long orderId, OrderType type, long quantity, long filled, long resting, long canceled,
                           boolean selfTradePrevented) {
        String message;
        if (selfTradePrevented)
            message = "내 반대 주문과 체결될 수 없어 %d주를 취소했어요 (자기매매 방지)".formatted(canceled);
        else if (type == OrderType.MARKET && canceled > 0)
            message = filled > 0 ? "%d주 체결, 남은 %d주는 취소됐어요".formatted(filled, canceled)
                    : "체결할 호가가 없어 취소됐어요";
        else if (filled == quantity) message = "%d주 모두 체결됐어요".formatted(quantity);
        else if (filled > 0) message = "%d주 체결, %d주는 대기 중이에요".formatted(filled, resting);
        else message = "주문이 접수됐어요. 호가창에서 대기 중이에요";
        return new OrderReceipt(orderId, filled, resting, canceled, selfTradePrevented, message);
    }
}
