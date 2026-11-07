package com.teamtrading.monitoring;

import com.teamtrading.engine.OrderType;
import com.teamtrading.engine.Side;
import com.teamtrading.trading.TradingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "teamtrading.monitoring.reconcile=true",
                "teamtrading.monitoring.reconcile-every=PT1H"})
class MonitoringTest {

    @LocalServerPort int port;
    @LocalManagementPort int managementPort;
    @Autowired ReconciliationMonitor monitor;
    @Autowired TradingService trading;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry registry;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(int port, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /*
     * 테스트 컨텍스트는 메트릭 내보내기(Prometheus 스크레이프)를 끈다. 미터 값은 레지스트리로 확인하고,
     * 관리 포트 분리는 health로 확인한다. 실제 스크레이프는 docs/operations.md의 로컬 점검 절차로 본다.
     */
    @Test
    void recordsTradingAndReconciliationMetricsAndServesHealthOnlyOnTheManagementPort() throws Exception {
        String seller = trading.openAccount("m-seller").id();
        String buyer = trading.openAccount("m-buyer").id();
        trading.grantShares(seller, "nc", 5, 11_600);
        trading.place(seller, "m-s", "nc", Side.SELL, OrderType.LIMIT, 11_600, 2);
        trading.place(buyer, "m-b", "nc", Side.BUY, OrderType.LIMIT, 11_600, 2);
        monitor.run();

        assertThat(balanced()).isEqualTo(1.0);
        assertThat(registry.get("teamtrading.reconciliation.mismatches").gauge().value()).isZero();
        assertThat(registry.get("teamtrading.trades").tag("team", "nc").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("teamtrading.trade.shares").tag("team", "nc").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("teamtrading.trade.points").tag("team", "nc").counter().count()).isEqualTo(23_200.0);
        assertThat(registry.get("teamtrading.markets.halted").gauge().value()).isZero();
        assertThat(registry.get("teamtrading.feed.sessions").gauge().value()).isZero();

        HttpResponse<String> health = get(managementPort, "/actuator/health");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"UP\"");
        assertThat(get(port, "/actuator/health").statusCode()).isIn(401, 404);
    }

    @Test
    void reportsAMismatchWhenABalanceIsChangedBehindTheLedger() {
        String victim = trading.openAccount("m-victim").id();
        jdbc.update("UPDATE accounts SET available_points = available_points + 1 WHERE id = ?", victim);
        try {
            monitor.run();
            assertThat(balanced()).isZero();
            assertThat(registry.get("teamtrading.reconciliation.mismatches").gauge().value()).isPositive();
        } finally {
            jdbc.update("UPDATE accounts SET available_points = available_points - 1 WHERE id = ?", victim);
            monitor.run();
        }
        assertThat(balanced()).isEqualTo(1.0);
    }

    private double balanced() {
        return registry.get("teamtrading.reconciliation.balanced").gauge().value();
    }
}
