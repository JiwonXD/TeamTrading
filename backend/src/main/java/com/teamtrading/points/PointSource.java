package com.teamtrading.points;

/**
 * 포인트가 새로 생기는 경로. 경로마다 1회 지급액과 하루(KST) 한도를 둔다.
 * 지급액은 시작값이며 운영 데이터를 보고 조정한다(docs/rulebook.md).
 */
public enum PointSource {

    /** 가입 지급. 계정당 한 번, sourceRef는 계정 id */
    SIGNUP(1_000_000, 1),
    /** 광고 시청. 광고 네트워크의 서버 측 검증 콜백을 받은 뒤에만 지급, sourceRef는 콜백의 거래 id */
    AD_VIEW(1_000, 10),
    /** 종목토론방 게시글. sourceRef는 게시글 id */
    POST(2_000, 3);

    private final long amount;
    private final int dailyLimit;

    PointSource(long amount, int dailyLimit) {
        this.amount = amount;
        this.dailyLimit = dailyLimit;
    }

    public long amount() {
        return amount;
    }

    public int dailyLimit() {
        return dailyLimit;
    }
}
