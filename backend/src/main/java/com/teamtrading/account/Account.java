package com.teamtrading.account;

/** 계좌 조회용 값. 잔고 변경은 AccountStore의 조건부 UPDATE로만 한다 */
public record Account(String id, String nickname, long initialPoints, long availablePoints, long lockedPoints) {
}
