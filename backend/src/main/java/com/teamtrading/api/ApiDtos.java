package com.teamtrading.api;

import com.teamtrading.account.Holding;
import com.teamtrading.engine.Level;
import com.teamtrading.engine.Order;
import com.teamtrading.engine.OrderType;
import com.teamtrading.engine.Side;
import com.teamtrading.engine.Trade;
import com.teamtrading.fds.Alert;
import com.teamtrading.trading.Quote;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** API 요청·응답 형식. 도메인 객체를 그대로 내보내지 않고, 상대 계정 정보는 빼고 내보낸다 */
final class ApiDtos {

    private ApiDtos() {
    }

    record OpenAccountRequest(@NotBlank @Size(max = 20) String nickname) {
    }

    record AccountResponse(String id, String nickname, long availablePoints, long lockedPoints,
                           long totalValue, double returnRate, List<Holding> holdings) {
    }

    record PlaceOrderRequest(@NotBlank @Size(max = 64) String clientOrderId, @NotBlank String teamId,
                             @NotNull Side side, @NotNull OrderType type,
                             @PositiveOrZero long price, @Positive long quantity) {
    }

    record OrderResponse(long id, String teamId, Side side, OrderType type, long price,
                         long quantity, long remaining, Instant createdAt) {
        static OrderResponse of(Order o) {
            return new OrderResponse(o.id(), o.teamId(), o.side(), o.type(), o.price(),
                    o.quantity(), o.remaining(), o.createdAt());
        }
    }

    record QuoteResponse(String id, String name, String shortName, String league, long basePrice,
                         long lastPrice, double changeRate, long high, long low, long volume,
                         long upperLimit, long lowerLimit) {
        static QuoteResponse of(Quote q) {
            var t = q.team();
            return new QuoteResponse(t.id(), t.name(), t.shortName(), t.league().name(), t.basePrice(),
                    q.lastPrice(), q.changeRate(), q.high(), q.low(), q.volume(), q.upperLimit(), q.lowerLimit());
        }
    }

    record OrderBookResponse(List<Level> asks, List<Level> bids, long lastPrice) {
    }

    /** 익명 체결 내역: 매수·매도 계정은 보여주지 않는다 */
    record TradeResponse(long id, long price, long quantity, Side aggressor, Instant executedAt) {
        static TradeResponse of(Trade t) {
            return new TradeResponse(t.id(), t.price(), t.quantity(), t.aggressor(), t.executedAt());
        }
    }

    record RankingEntry(int rank, String nickname, long totalValue, double returnRate) {
    }

    record AlertResponse(String kind, String teamId, long tradeId, String accountA, String accountB,
                         String message, Instant detectedAt) {
        static AlertResponse of(Alert a) {
            return new AlertResponse(a.kind().name(), a.teamId(), a.tradeId(), a.accountA(), a.accountB(),
                    a.message(), a.detectedAt());
        }
    }

    record ErrorResponse(String message) {
    }
}
