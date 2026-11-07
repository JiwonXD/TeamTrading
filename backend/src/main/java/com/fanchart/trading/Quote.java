package com.fanchart.trading;

import com.fanchart.team.Team;

import java.time.Instant;

/**
 * @param basePrice 기준가(전일 종가). 등락률과 가격제한폭의 기준
 * @param listed    거래 중인지. 첫 상장 공모 전이거나 상장 동시호가를 기다리면 false
 * @param indicativePrice  단일가 매매(상장 동시호가, VI) 중 예상 체결가. 단일가 매매가 아니거나 체결될 주문이 없으면 null
 * @param indicativeVolume 예상 체결가에 체결될 수량
 * @param viUntil          VI가 발동해 단일가 매매 중이면 끝나는 시각, 아니면 null
 */
public record Quote(Team team, long basePrice, long lastPrice, long high, long low, long volume, long upperLimit, long lowerLimit,
                    boolean listed, Long indicativePrice, long indicativeVolume, Instant viUntil) {

    public double changeRate() {
        return (double) lastPrice / basePrice - 1;
    }
}
