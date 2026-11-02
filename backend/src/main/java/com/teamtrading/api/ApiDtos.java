package com.teamtrading.api;

import com.teamtrading.account.Holding;
import com.teamtrading.engine.Level;
import com.teamtrading.engine.OrderType;
import com.teamtrading.engine.Side;
import com.teamtrading.engine.Trade;
import com.teamtrading.fds.Alert;
import com.teamtrading.trading.MarketUpdate;
import com.teamtrading.trading.Quote;
import com.teamtrading.trading.StoredOrder;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;

/** API 요청·응답 형식. 도메인 객체를 그대로 내보내지 않고, 상대 계정 정보는 빼고 내보낸다 */
final class ApiDtos {

    private ApiDtos() {
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
        static OrderResponse of(StoredOrder o) {
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

    /** epoch·seq는 실시간 시세의 번호와 같아서, 이 응답 뒤에 받은 업데이트 중 어디부터 이어 붙일지 알 수 있다 */
    record OrderBookResponse(List<Level> asks, List<Level> bids, long lastPrice, long epoch, long seq) {
    }

    /** 실시간 시세 메시지. 접속하면 snapshot 한 번, 그 뒤로 바뀔 때마다 update */
    record FeedMessage(String type, String teamId, long epoch, long seq, QuoteResponse quote,
                       List<Level> asks, List<Level> bids, List<TradeResponse> trades) {
        static FeedMessage of(String type, MarketUpdate u) {
            return new FeedMessage(type, u.teamId(), u.epoch(), u.seq(), QuoteResponse.of(u.quote()), u.asks(),
                    u.bids(), u.trades().stream().map(TradeResponse::of).toList());
        }
    }

    /** 익명 체결 내역: 매수·매도 계정은 보여주지 않는다 */
    record TradeResponse(long id, long price, long quantity, Side aggressor, Instant executedAt) {
        static TradeResponse of(Trade t) {
            return new TradeResponse(t.id(), t.price(), t.quantity(), t.aggressor(), t.executedAt());
        }
    }

    /** me: 요청한 계정의 행인지. 계정 id는 지금 인증 수단이라 내보내지 않는다 */
    record RankingEntry(int rank, String nickname, long totalValue, double returnRate, boolean me) {
    }

    /** 내 체결. side는 내가 산 쪽인지 판 쪽인지 */
    record MyTradeResponse(long id, String teamId, Side side, long price, long quantity, Instant executedAt) {
        static MyTradeResponse of(Trade t, String accountId) {
            return new MyTradeResponse(t.id(), t.teamId(), t.buyerId().equals(accountId) ? Side.BUY : Side.SELL,
                    t.price(), t.quantity(), t.executedAt());
        }
    }

    /** 계정은 닉네임으로 보여준다. 계정 id는 지금 인증 수단이라 내보내면 그 계정으로 주문할 수 있다 */
    record AlertResponse(String kind, String teamId, long tradeId, String nicknameA, String nicknameB,
                         String message, Instant detectedAt) {
        static AlertResponse of(Alert a, Function<String, String> nickname) {
            return new AlertResponse(a.kind().name(), a.teamId(), a.tradeId(), nickname.apply(a.accountA()),
                    nickname.apply(a.accountB()), a.message(), a.detectedAt());
        }
    }

    record ErrorResponse(String message) {
    }
}
