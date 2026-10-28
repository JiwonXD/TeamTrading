package com.teamtrading.account;

/**
 * @param available 판매 주문에 묶이지 않은 수량
 * @param locked    대기 중인 판매 주문에 묶인 수량
 */
public record Holding(String teamId, long available, long locked, long averagePrice) {

    public long total() {
        return available + locked;
    }
}
