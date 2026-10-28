package com.teamtrading.api;

import com.jayway.jsonpath.JsonPath;
import com.teamtrading.engine.OrderType;
import com.teamtrading.engine.Side;
import com.teamtrading.trading.TradingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MarketFeedTest {

    @LocalServerPort int port;
    @Autowired TradingService trading;

    private final BlockingQueue<String> received = new LinkedBlockingQueue<>();
    private final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();

    private WebSocketSession connect(String teamId) throws Exception {
        return new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                received.add(message.getPayload());
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                closed.complete(status);
            }
        }, "ws://localhost:" + port + "/ws/teams/" + teamId).get(5, TimeUnit.SECONDS);
    }

    private String next() throws InterruptedException {
        String message = received.poll(5, TimeUnit.SECONDS);
        assertThat(message).isNotNull();
        return message;
    }

    @Test
    void sendsSnapshotThenNumberedUpdates() throws Exception {
        String seller = trading.openAccount("feed-seller").id();
        String buyer = trading.openAccount("feed-buyer").id();
        trading.grantShares(seller, "doosan", 10, 14_850);
        WebSocketSession session = connect("doosan");

        String snapshot = next();
        assertThat((String) JsonPath.read(snapshot, "$.type")).isEqualTo("snapshot");
        long seq = ((Number) JsonPath.read(snapshot, "$.seq")).longValue();

        trading.place(seller, "f-1", "doosan", Side.SELL, OrderType.LIMIT, 14_850, 5);
        trading.place(buyer, "f-2", "doosan", Side.BUY, OrderType.LIMIT, 14_850, 2);

        String first = next();
        assertThat((String) JsonPath.read(first, "$.type")).isEqualTo("update");
        assertThat(((Number) JsonPath.read(first, "$.seq")).longValue()).isEqualTo(seq + 1);
        assertThat(((Number) JsonPath.read(first, "$.asks[0].quantity")).longValue()).isEqualTo(5);

        String second = next();
        assertThat(((Number) JsonPath.read(second, "$.seq")).longValue()).isEqualTo(seq + 2);
        assertThat(((Number) JsonPath.read(second, "$.trades[0].quantity")).longValue()).isEqualTo(2);
        assertThat(((Number) JsonPath.read(second, "$.quote.lastPrice")).longValue()).isEqualTo(14_850);
        // 체결 내역은 익명이다
        assertThat(second).doesNotContain(buyer).doesNotContain(seller);
        session.close();
    }

    @Test
    void closesUnknownTeam() throws Exception {
        connect("nope");
        assertThat(closed.get(5, TimeUnit.SECONDS).getCode()).isEqualTo(CloseStatus.BAD_DATA.getCode());
    }
}
