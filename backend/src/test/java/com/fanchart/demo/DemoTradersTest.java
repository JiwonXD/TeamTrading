package com.fanchart.demo;

import com.fanchart.ledger.Reconciliation;
import com.fanchart.trading.TradingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("demo")
class DemoTradersTest {

    @Autowired DemoTraders demo;
    @Autowired TradingService trading;
    @Autowired Reconciliation reconciliation;
    @Autowired JdbcTemplate jdbc;

    @Test
    void tradesOnlyThroughOrdersAndKeepsLedgerBalanced() {
        for (int i = 0; i < 200; i++) demo.tick();

        assertThat(trading.allAccounts()).extracting(a -> a.nickname()).containsAll(DemoTraders.NAMES);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trades", Long.class)).isPositive();
        assertThat(reconciliation.run().balanced()).isTrue();

        // 재시작해도 계정을 새로 만들지 않는다
        demo.openAccounts();
        assertThat(trading.allAccounts()).hasSize(DemoTraders.NAMES.size());
    }
}
