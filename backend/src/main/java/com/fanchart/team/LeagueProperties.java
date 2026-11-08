package com.fanchart.team;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.EnumSet;
import java.util.Set;

/**
 * 리그 공개 설정(ADR 0033).
 *
 * @param hidden 닫아 둔 리그. 받을 경기 데이터가 없어 배당을 줄 수 없는 리그다. 그 리그 정규 시즌은 수집하지 않고,
 *               화면은 리그 탭과 구단 목록에서 뺀다. 구단과 보유·주문은 그대로 두므로 가진 사람은 구단 화면에서 팔 수 있다
 */
@ConfigurationProperties("fanchart.leagues")
public record LeagueProperties(Set<League> hidden) {

    public LeagueProperties {
        hidden = hidden == null || hidden.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(hidden));
    }

    public boolean hidden(League league) {
        return league != null && hidden.contains(league);
    }
}
