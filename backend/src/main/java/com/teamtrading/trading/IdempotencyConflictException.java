package com.teamtrading.trading;

/** 이미 쓴 주문 번호를 다른 내용의 주문에 다시 쓴 경우 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String message) {
        super(message);
    }
}
