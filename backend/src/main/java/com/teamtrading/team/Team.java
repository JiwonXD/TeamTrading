package com.teamtrading.team;

/**
 * @param basePrice   첫 기준가. 일 마감 종가가 생기면 그 값이 기준가가 된다(시장의 현재 기준가는 Quote.basePrice)
 * @param color       화면의 구단 색(#rrggbb)
 * @param ipoRequired 운영자가 새로 등록한 구단. 첫 상장 공모로 주식이 생기기 전까지 거래하지 않는다
 */
public record Team(String id, String name, String shortName, League league, long basePrice, String color,
                   boolean ipoRequired) {
}
