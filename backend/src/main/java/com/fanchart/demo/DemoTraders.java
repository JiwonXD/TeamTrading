package com.fanchart.demo;

import com.fanchart.account.Account;
import com.fanchart.engine.OrderType;
import com.fanchart.engine.PriceRules;
import com.fanchart.engine.Side;
import com.fanchart.team.Team;
import com.fanchart.team.TeamCatalog;
import com.fanchart.trading.NotFoundException;
import com.fanchart.trading.StoredOrder;
import com.fanchart.trading.TradingException;
import com.fanchart.trading.TradingService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 데모용 시뮬레이션 계정. 사람이 적은 개발·시연 환경에서 호가창이 비지 않도록, 일반 계정과 똑같이
 * 주문 API로만 매매한다. 시스템이 호가를 받아주는 마켓메이커가 아니라 다른 유저 역할이다.
 * demo 프로필에서만 켜진다.
 */
@Configuration
@Profile("demo")
@EnableScheduling
class DemoTraders {

    static final List<String> NAMES = List.of(
            "잠실불꽃", "광주호랑이", "보살팬", "페이커장인", "호랑이굴", "쌍둥이아빠", "사직노래방", "대구왕자",
            "마법사의제자", "인천상륙", "창원공룡", "고척돔지기", "스틸야드", "상암직관러", "전주성", "midgap",
            "골드카드", "야구는9회말", "롤드컵가자", "배당장인");
    private static final Duration STALE = Duration.ofMinutes(2);
    private static final int MAX_TICKS_AWAY = 12;

    private final TradingService trading;
    private final List<Team> teams;
    private final Clock clock;
    private final Random random = new Random();
    private final Map<String, Double> bias = new HashMap<>();
    private List<String> bots = List.of();

    DemoTraders(TradingService trading, TeamCatalog catalog, Clock clock) {
        this.trading = trading;
        this.teams = catalog.all();
        this.clock = clock;
    }

    /** 처음 뜰 때만 계정을 만들고 구단별 초기 보유분을 지급한다. 재시작하면 있던 계정을 그대로 쓴다 */
    @EventListener(ApplicationReadyEvent.class)
    void openAccounts() {
        Set<String> names = Set.copyOf(NAMES);
        Map<String, String> existing = trading.allAccounts().stream().filter(a -> names.contains(a.nickname()))
                .collect(Collectors.toMap(Account::nickname, Account::id, (a, b) -> a));
        for (String name : NAMES) {
            if (existing.containsKey(name)) continue;
            String id = trading.openAccount(name).id();
            for (Team t : teams) trading.grantShares(id, t.id(), 30 + random.nextInt(50), t.basePrice());
            existing.put(name, id);
        }
        bots = List.copyOf(existing.values());
        for (Team t : teams) bias.put(t.id(), (random.nextDouble() - 0.5) * 1.2);
    }

    @Scheduled(fixedDelay = 1_500, initialDelay = 3_000)
    void tick() {
        if (bots.isEmpty()) return;
        tidyUp();
        for (int i = 0; i < 3; i++) placeRandomOrder();
    }

    private void placeRandomOrder() {
        Team team = teams.get(random.nextInt(teams.size()));
        String bot = bots.get(random.nextInt(bots.size()));
        double b = Math.clamp(bias.get(team.id()) + (random.nextDouble() - 0.5) * 0.3, -1, 1);
        bias.put(team.id(), b);
        Side side = random.nextDouble() < 0.5 + b * 0.25 ? Side.BUY : Side.SELL;
        try {
            if (random.nextDouble() < 0.12) {
                trading.place(bot, UUID.randomUUID().toString(), team.id(), side, OrderType.MARKET, 0,
                        1 + random.nextInt(5));
                return;
            }
            boolean aggressive = random.nextDouble() < 0.4;
            int ticks = aggressive ? -random.nextInt(3) : 1 + random.nextInt(5);
            var quote = trading.quote(team.id());
            long last = quote.lastPrice();
            long price = step(last, side == Side.BUY ? -ticks : ticks);
            price = Math.clamp(price, quote.lowerLimit(), quote.upperLimit());
            trading.place(bot, UUID.randomUUID().toString(), team.id(), side, OrderType.LIMIT, price,
                    1 + random.nextInt(15));
        } catch (TradingException expected) {
            // 포인트나 수량이 모자란 계정의 주문은 거절된다
        }
    }

    /** 오래됐거나 현재가에서 멀어진 주문을 취소해서 호가가 현재가 주변에 머물게 한다 */
    private void tidyUp() {
        var now = clock.instant();
        for (String bot : bots) {
            for (StoredOrder o : trading.openOrders(bot)) {
                long last = trading.quote(o.teamId()).lastPrice();
                boolean far = Math.abs(o.price() - last) > PriceRules.tickSize(last) * MAX_TICKS_AWAY;
                boolean stale = o.createdAt().plus(STALE).isBefore(now) && random.nextDouble() < 0.3;
                if (!far && !stale) continue;
                try {
                    trading.cancel(bot, o.id());
                } catch (NotFoundException alreadyFilled) {
                    // 그 사이 체결됐다
                }
            }
        }
    }

    private static long step(long price, int ticks) {
        long p = price;
        for (int i = 0; i < Math.abs(ticks); i++) {
            long tick = PriceRules.tickSize(ticks > 0 ? p : p - 1);
            p = ticks > 0 ? p + tick : p - tick;
        }
        return p;
    }
}
