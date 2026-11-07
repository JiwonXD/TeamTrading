package com.teamtrading.trading;

/** 사용자가 고칠 수 있는 주문 거절 사유. 메시지는 그대로 화면에 보여준다 */
public class TradingException extends RuntimeException {

    public TradingException(String message) {
        super(message);
    }
}
