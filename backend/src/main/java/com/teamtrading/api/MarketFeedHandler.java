package com.teamtrading.api;

import com.teamtrading.api.ApiDtos.FeedMessage;
import com.teamtrading.trading.MarketUpdate;
import com.teamtrading.trading.NotFoundException;
import com.teamtrading.trading.TradingService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 구단별 실시간 시세. /ws/teams/{teamId}로 접속하면 스냅숏을 한 번 받고 이후 업데이트를 받는다.
 * 업데이트는 종목 락 안에서 만들어지므로 여기서는 세션별 큐에 넣기만 하고, 실제 전송은 세션마다 둔
 * 가상 스레드가 한다. 느린 클라이언트가 매칭을 막지 않고, 큐가 넘친 세션은 끊는다.
 * 끊긴 클라이언트는 다시 접속해 새 스냅숏부터 받으면 된다.
 */
@Component
class MarketFeedHandler extends TextWebSocketHandler {

    private static final int QUEUE_LIMIT = 256;
    private static final int SNAPSHOT_TRADES = 30;

    private final TradingService trading;
    private final ObjectMapper json;
    /** teamId → (세션 id → 구독자) */
    private final Map<String, Map<String, Subscriber>> subscribers = new ConcurrentHashMap<>();

    MarketFeedHandler(TradingService trading, ObjectMapper json) {
        this.trading = trading;
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        String path = session.getUri().getPath();
        String teamId = path.substring(path.lastIndexOf('/') + 1);
        session.getAttributes().put("teamId", teamId);
        try {
            trading.subscribe(teamId, SNAPSHOT_TRADES, snapshot -> {
                Subscriber subscriber = new Subscriber(session);
                subscribers.computeIfAbsent(teamId, k -> new ConcurrentHashMap<>()).put(session.getId(), subscriber);
                subscriber.offer(json.writeValueAsString(FeedMessage.of("snapshot", snapshot)));
            });
        } catch (NotFoundException e) {
            session.close(CloseStatus.BAD_DATA.withReason("unknown team"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Map<String, Subscriber> team = subscribers.get((String) session.getAttributes().get("teamId"));
        Subscriber subscriber = team == null ? null : team.remove(session.getId());
        if (subscriber != null) subscriber.sender.interrupt();
    }

    /** TradingService가 종목 락 안에서 동기로 호출한다 */
    @EventListener
    void onUpdate(MarketUpdate update) {
        Map<String, Subscriber> team = subscribers.get(update.teamId());
        if (team == null || team.isEmpty()) return;
        String message = json.writeValueAsString(FeedMessage.of("update", update));
        for (Subscriber s : team.values()) s.offer(message);
    }

    private static final class Subscriber {

        final WebSocketSession session;
        final BlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_LIMIT);
        final Thread sender;

        Subscriber(WebSocketSession session) {
            this.session = session;
            this.sender = Thread.ofVirtual().start(this::drain);
        }

        /** 락 안에서 불리므로 막히는 일(전송, 연결 닫기)은 하지 않고 전송 스레드에 맡긴다 */
        void offer(String message) {
            if (!queue.offer(message)) sender.interrupt();
        }

        private void drain() {
            try {
                while (session.isOpen()) session.sendMessage(new TextMessage(queue.take()));
            } catch (InterruptedException | IOException | RuntimeException e) {
                // 큐가 넘쳤거나 전송이 실패했다. 이미 닫힌 연결이면 아무 일도 없다
                close(CloseStatus.SESSION_NOT_RELIABLE);
            }
        }

        private void close(CloseStatus status) {
            try {
                session.close(status);
            } catch (IOException ignored) {
                // 이미 끊긴 세션이다
            }
        }
    }
}
