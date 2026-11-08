package com.fanchart.trading;

import com.fanchart.auth.AuthProperties;
import com.fanchart.engine.OrderType;
import com.fanchart.engine.PriceRules;
import com.fanchart.engine.Side;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * 안 팔린 운영자 매도는 다음날 할인해서 다시 건다(ADR 0038).
 * 매일 점검시간이 끝나는 00:30(한국 시간)에, 남아 있는 운영자 매도 주문을 모두 정해진 비율만큼 내린 가격으로 다시 낸다.
 * 바닥 가격과 그날 하한가 아래로는 내리지 않는다.
 */
@Component
class IssueMarkdown {

    private static final Logger log = LoggerFactory.getLogger(IssueMarkdown.class);
    static final String PREFIX = "markdown-";

    private final TradingService trading;
    private final AuthProperties auth;
    private final Clock clock;
    private final boolean enabled;
    private final int percent;
    private final long floor;

    IssueMarkdown(TradingService trading, AuthProperties auth, Clock clock,
                  @Value("${fanchart.issuance.markdown.enabled:true}") boolean enabled,
                  @Value("${fanchart.issuance.markdown.percent:5}") int percent,
                  @Value("${fanchart.issuance.markdown.floor:5000}") long floor) {
        this.trading = trading;
        this.auth = auth;
        this.clock = clock;
        this.enabled = enabled;
        this.percent = percent;
        this.floor = floor;
    }

    @Scheduled(cron = "5 30 0 * * *", zone = "Asia/Seoul")
    void scheduled() {
        if (!enabled) return;
        try {
            int repriced = run();
            if (repriced > 0) log.info("marked down {} unsold operator sell orders", repriced);
        } catch (RuntimeException e) {
            log.error("operator markdown failed", e);
        }
    }

    /** 오늘 할인을 한다. 같은 날 다시 돌려도 이미 내린 주문은 건너뛴다. 다시 건 주문 수를 돌려준다 */
    int run() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), MarketHours.ZONE);
        String todayPrefix = PREFIX + today + "-";
        int repriced = 0;
        for (String issuer : auth.admins()) {
            for (StoredOrder o : trading.openOrders(issuer)) {
                if (o.side() != Side.SELL || o.type() != OrderType.LIMIT) continue;
                if (o.clientOrderId().startsWith(todayPrefix)) continue;
                long price = discounted(o.price(), trading.quote(o.teamId()).lowerLimit());
                if (price >= o.price()) continue;
                try {
                    trading.repriceIssue(issuer, o.id(), price, todayPrefix + o.id());
                    repriced++;
                } catch (TradingException | NotFoundException e) {
                    log.warn("could not mark down order {} of {}: {}", o.id(), o.teamId(), e.getMessage());
                }
            }
        }
        return repriced;
    }

    /** percent% 내린 가격을 호가 단위로 내림한다. 바닥 가격과 하한가 중 높은 쪽 아래로는 내리지 않는다 */
    long discounted(long price, long lowerLimit) {
        long raw = price * (100 - percent) / 100;
        long down = raw / PriceRules.tickSize(raw) * PriceRules.tickSize(raw);
        long min = Math.max(floor, lowerLimit);
        long tick = PriceRules.tickSize(min);
        return Math.max(down, (min + tick - 1) / tick * tick);
    }
}
