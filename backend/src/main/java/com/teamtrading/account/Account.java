package com.teamtrading.account;

/**
 * 계좌 조회용 값. 잔고 변경은 AccountStore의 조건부 UPDATE로만 한다.
 * initialValue는 수익률의 기준으로, 시작 포인트와 시즌 초 지급받은 주식의 지급가 평가액을 더한 값이다.
 */
public record Account(String id, String nickname, long initialValue, long availablePoints, long lockedPoints) {
}
