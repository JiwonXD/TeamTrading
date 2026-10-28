package com.teamtrading.trading;

public record OrderReceipt(long orderId, long filledQuantity, long restingQuantity, long canceledQuantity,
                           boolean selfTradePrevented, String message) {
}
