package com.teamtrading.trading;

import com.teamtrading.TestClock;
import com.teamtrading.account.Account;
import com.teamtrading.account.AccountStore;
import com.teamtrading.engine.OrderType;
import com.teamtrading.engine.Side;
import com.teamtrading.engine.Trade;
import com.teamtrading.fds.Alert;
import com.teamtrading.fds.AlertKind;
import com.teamtrading.fds.AlertStore;
import com.teamtrading.ledger.Ledger;
import com.teamtrading.ledger.Reconciliation;
import com.teamtrading.points.PointIssuer;
import com.teamtrading.team.TeamCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/** 변동성 완화장치(VI). lg 기준가 18,450: 동적 VI 범위는 직전 체결가 ±6% 미만, 정적 VI 범위는 18,450 ±10% 미만 */
@SpringBootTest
@Import(TestClock.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class VolatilityInterruptionTest {

    static final String TEAM = "lg";

    @Autowired TradingService trading;
    @Autowired TradeStore trades;
    @Autowired TeamCatalog catalog;
    @Autowired AccountStore accounts;
    @Autowired OrderStore orders;
    @Autowired Ledger ledger;
    @Autowired PointIssuer points;
    @Autowired TransactionTemplate tx;
    @Autowired ListingStore listings;
    @Autowired AlertStore alertStore;
    @Autowired Reconciliation reconciliation;
    @Autowired TestClock clock;

    private String buyer;
    private String seller;

    @BeforeEach
    void setUp() {
        clock.set(clock.instant().plus(Duration.ofHours(12)));
        buyer = trading.openAccount("vi-buyer").id();
        seller = trading.openAccount("vi-seller").id();
        trading.grantShares(seller, TEAM, 100, 18_450);
    }

    private OrderReceipt order(String account, Side side, OrderType type, long price, long qty) {
        return trading.place(account, UUID.randomUUID().toString(), TEAM, side, type, price, qty);
    }

    private OrderReceipt limit(String account, Side side, long price, long qty) {
        return order(account, side, OrderType.LIMIT, price, qty);
    }

    /** 18,450에 2주, 20,300(동적·정적 범위 밖)에 5주를 팔고, 20,300에 4주를 사서 VI를 일으킨다 */
    private OrderReceipt triggerVi() {
        limit(seller, Side.SELL, 18_450, 2);
        limit(seller, Side.SELL, 20_300, 5);
        return limit(buyer, Side.BUY, 20_300, 4);
    }

    @Test
    void aTradeOutsideTheBandStopsContinuousTradingForTwoMinutesThenSettlesAtOnePrice() {
        OrderReceipt receipt = triggerVi();

        // 범위 안 18,450 두 주만 체결하고, 남은 두 주는 단일가 매매 호가로 남는다
        assertThat(receipt.filledQuantity()).isEqualTo(2);
        assertThat(receipt.restingQuantity()).isEqualTo(2);
        Quote q = trading.quote(TEAM);
        assertThat(q.viUntil()).isEqualTo(clock.instant().plus(TradingService.VI_DURATION));
        assertThat(q.indicativePrice()).isEqualTo(20_300);
        assertThat(q.indicativeVolume()).isEqualTo(2);
        assertThat(q.listed()).isTrue();

        assertThatThrownBy(() -> order(buyer, Side.BUY, OrderType.MARKET, 0, 1)).hasMessageContaining("VI");
        assertThatThrownBy(() -> limit(buyer, Side.SELL, 20_300, 1)).hasMessageContaining("반대 주문");
        // VI 중 들어온 지정가는 체결하지 않고 모은다
        assertThat(limit(seller, Side.SELL, 19_700, 1).filledQuantity()).isZero();

        clock.advance(TradingService.VI_DURATION.minusSeconds(1));
        trading.openDueAuctions();
        assertThat(trading.quote(TEAM).viUntil()).isNotNull();

        clock.advance(Duration.ofSeconds(1));
        trading.openDueAuctions();
        assertThat(trading.quote(TEAM).viUntil()).isNull();
        assertThat(trades.recent(TEAM, 10)).extracting(Trade::price, Trade::quantity)
                .containsExactly(tuple(20_300L, 1L), tuple(20_300L, 1L), tuple(18_450L, 2L));
        assertThat(trading.quote(TEAM).lastPrice()).isEqualTo(20_300);

        Account b = trading.account(buyer);
        assertThat(b.availablePoints()).isEqualTo(TradingService.START_POINTS - 2 * 18_450 - 2 * 20_300);
        assertThat(b.lockedPoints()).isZero();
        assertThat(reconciliation.run().balanced()).isTrue();
    }

    @Test
    void anAuctionPriceFarFromThePriceBeforeTheInterruptionIsFlagged() {
        triggerVi();
        clock.advance(TradingService.VI_DURATION);
        trading.openDueAuctions();

        // 20,300은 VI 직전 체결가 18,450보다 10% 넘게 높다. 얇은 호가에서 단일가 매매로 몰아주기를 피해 가지 못하게 기록한다
        assertThat(trading.alerts(10)).extracting(Alert::kind).contains(AlertKind.PRICE_DEVIATION);
    }

    @Test
    void driftingTenPercentFromTheBasePriceInSmallStepsTriggersTheStaticInterruption() {
        for (long price : new long[]{19_000, 19_800}) {
            limit(seller, Side.SELL, price, 1);
            assertThat(limit(buyer, Side.BUY, price, 1).filledQuantity()).isEqualTo(1);
        }
        assertThat(trading.quote(TEAM).viUntil()).isNull();

        // 직전 체결가 19,800에서는 2.5%지만 기준가 18,450에서는 10% 넘게 오른 가격
        limit(seller, Side.SELL, 20_300, 1);
        assertThat(limit(buyer, Side.BUY, 20_300, 1).filledQuantity()).isZero();
        assertThat(trading.quote(TEAM).viUntil()).isNotNull();
    }

    @Test
    void aRestartDuringAnInterruptionRestartsItInsteadOfTradingACrossedBook() {
        triggerVi();
        clock.advance(Duration.ofSeconds(30));

        // 새로 뜬 서버: VI 끝 시각은 메모리에만 있었으므로 DB의 대기 주문만 보고 다시 판단한다
        TradingService restarted = new TradingService(catalog, accounts, orders, trades, ledger, points, tx, clock,
                e -> {}, listings, alertStore);
        restarted.recover();
        trading = restarted;

        Quote q = trading.quote(TEAM);
        assertThat(q.viUntil()).isEqualTo(clock.instant().plus(TradingService.VI_DURATION));
        assertThat(q.indicativePrice()).isEqualTo(20_300);
        assertThatThrownBy(() -> order(buyer, Side.BUY, OrderType.MARKET, 0, 1)).hasMessageContaining("VI");

        clock.advance(TradingService.VI_DURATION);
        trading.openDueAuctions();
        assertThat(trades.recent(TEAM, 10)).extracting(Trade::price).containsExactly(20_300L, 18_450L);
        assertThat(reconciliation.run().balanced()).isTrue();
    }

    @Test
    void theFirstOrderAfterTheInterruptionEndsSettlesTheAuctionBeforeItIsMatched() {
        triggerVi();
        clock.advance(TradingService.VI_DURATION);

        // 스케줄러가 돌기 전에 들어온 주문: 단일가 매매를 먼저 끝내고, 새 직전 체결가 기준으로 연속 매매한다
        OrderReceipt next = limit(buyer, Side.BUY, 20_300, 1);

        assertThat(trading.quote(TEAM).viUntil()).isNull();
        assertThat(next.filledQuantity()).isEqualTo(1);
        assertThat(trades.recent(TEAM, 10)).extracting(Trade::price).containsExactly(20_300L, 20_300L, 18_450L);
        assertThat(reconciliation.run().balanced()).isTrue();
    }
}
