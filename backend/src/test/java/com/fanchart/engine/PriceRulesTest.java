package com.fanchart.engine;

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

    @Test
    void rejectsQuantityAboveTheCapSoPriceTimesQuantityCannotOverflow() {
        assertThat(PriceRules.validate(OrderType.LIMIT, 10_000, PriceRules.MAX_QUANTITY, 10_000)).isEmpty();
        assertThat(PriceRules.validate(OrderType.LIMIT, 10_000, PriceRules.MAX_QUANTITY + 1, 10_000)).get().asString()
                .isEqualTo("한 번에 1,000,000주까지 주문할 수 있어요");
        assertThat(PriceRules.validate(OrderType.MARKET, 0, (1L << 60) + 1, 10_000)).isPresent();
    }

    @Test
    void volatilityBandStopsJustBeforeTheThresholdOnATick() {
        // 정확히 6%(10,600)는 발동 가격이라 범위에 넣지 않는다
        assertThat(PriceRules.viUpper(10_000, 6)).isEqualTo(10_590);
        assertThat(PriceRules.viLower(10_000, 6)).isEqualTo(9_410);
        assertThat(PriceRules.viUpper(18_450, 10)).isEqualTo(20_250);
        assertThat(PriceRules.viLower(18_450, 10)).isEqualTo(16_610);
        // 호가 단위가 바뀌는 경계(5,000 미만은 5원, 이상은 10원)
        assertThat(PriceRules.viUpper(4_800, 6)).isEqualTo(5_080);
        assertThat(PriceRules.viLower(5_300, 6)).isEqualTo(4_985);
    }
}
