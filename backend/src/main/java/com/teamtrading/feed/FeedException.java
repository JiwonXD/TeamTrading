package com.teamtrading.feed;

/** 제공처 응답을 쓸 수 없을 때. 이번 동기화는 아무것도 바꾸지 않고 다음 차례에 다시 시도한다 */
public class FeedException extends RuntimeException {

    public FeedException(String message) {
        super(message);
    }

    public FeedException(String message, Throwable cause) {
        super(message, cause);
    }
}
