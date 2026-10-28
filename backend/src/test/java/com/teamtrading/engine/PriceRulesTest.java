package com.teamtrading.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PriceRulesTest {

    @Test
    void priceBandIsThirtyPercentRoundedToTick() {
        assertThat(PriceRules.upperLimit(10_000)).isEqualTo(13_000);
        assertThat(PriceRules.lowerLimit(10_000)).isEqualTo(7_000);
        assertThat(PriceRules.upperLimit(18_450)).isEqualTo(23_950);
        assertThat(PriceRules.lowerLimit(18_450)).isEqualTo(12_920);
    }

    @Test
    void rejectsOrdersOutsideBandOrTick() {
        assertThat(PriceRules.validate(OrderType.LIMIT, 13_010, 1, 10_000)).get().asString().contains("상한가");
        assertThat(PriceRules.validate(OrderType.LIMIT, 6_990, 1, 10_000)).get().asString().contains("하한가");
        assertThat(PriceRules.validate(OrderType.LIMIT, 10_005, 1, 10_000)).get().asString().contains("호가 단위");
        assertThat(PriceRules.validate(OrderType.LIMIT, 10_010, 0, 10_000)).get().asString().contains("수량");
        assertThat(PriceRules.validate(OrderType.LIMIT, 10_010, 1, 10_000)).isEmpty();
        assertThat(PriceRules.validate(OrderType.MARKET, 0, 1, 10_000)).isEmpty();
    }
}
