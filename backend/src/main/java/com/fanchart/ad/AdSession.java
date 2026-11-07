package com.fanchart.ad;

import java.time.Instant;

/** 광고 시청 한 번. transactionRef는 완료될 때 정해지는 광고 네트워크의 거래 id(네트워크:id) */
public record AdSession(String id, String accountId, Status status, Instant createdAt, Instant completedAt,
                        String transactionRef) {

    public enum Status {
        /** 광고를 보는 중. 네트워크 콜백을 기다린다 */
        PENDING,
        /** 콜백을 받아 포인트를 줬다 */
        PAID,
        /** 콜백을 받았지만 오늘 한도를 넘어 주지 않았다 */
        LIMIT
    }
}
