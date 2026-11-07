package com.teamtrading.account;

import java.time.Instant;

/**
 * 계좌 조회용 값. 잔고 변경은 AccountStore의 조건부 UPDATE로만 한다.
 * initialValue는 수익률의 기준으로, 시작 포인트와 시즌 초 지급받은 주식의 지급가 평가액을 더한 값이다.
 * nicknameChangedAt은 마지막으로 닉네임을 바꾼 시각(바꾼 적 없으면 null), withdrawnAt은 탈퇴한 시각(탈퇴 전이면 null).
 */
public record Account(String id, String nickname, long initialValue, long availablePoints, long lockedPoints,
                      Instant nicknameChangedAt, Instant withdrawnAt) {

    public boolean withdrawn() {
        return withdrawnAt != null;
    }
}
