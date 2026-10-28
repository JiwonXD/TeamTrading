package com.teamtrading.team;

/** @param basePrice 전일 종가. 가격제한폭의 기준 */
public record Team(String id, String name, String shortName, League league, long basePrice) {
}
