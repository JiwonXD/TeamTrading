package com.teamtrading.trading;

import com.teamtrading.engine.Level;
import com.teamtrading.engine.Trade;

import java.util.List;

/**
 * 종목 하나의 시세 변화. seq는 종목마다 1씩 늘어서 받는 쪽이 빠진 업데이트를 알아챌 수 있다.
 * epoch은 서버가 뜰 때마다 바뀌므로 epoch이 달라지면 seq를 처음부터 다시 센다.
 * 호가는 매번 위에서부터 depth만큼 통째로 보내고, trades는 이번에 새로 난 체결만 담는다(최신이 앞).
 */
public record MarketUpdate(String teamId, long epoch, long seq, Quote quote, List<Level> asks, List<Level> bids,
                           List<Trade> trades) {
}
