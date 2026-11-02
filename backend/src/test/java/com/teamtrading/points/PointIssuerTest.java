package com.teamtrading.points;

import com.teamtrading.account.Account;
import com.teamtrading.account.AccountStore;
import com.teamtrading.ledger.Journal;
import com.teamtrading.ledger.Ledger;
import com.teamtrading.ledger.Reconciliation;
import com.teamtrading.trading.IdempotencyConflictException;
import com.teamtrading.trading.TradingException;
import com.teamtrading.trading.TradingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PointIssuerTest {

    /** 2026-11-02 23:30 KST. 30분 뒤면 다음 날이다 */
    static final Instant LATE_NIGHT = ZonedDateTime.of(2026, 11, 2, 23, 30, 0, 0, ZoneId.of("Asia/Seoul")).toInstant();

    static class MovableClock extends Clock {
        volatile Instant now = LATE_NIGHT;

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock();
        }
    }

    @Autowired PointIssuer issuer;
    @Autowired TradingService trading;
    @Autowired AccountStore accounts;
    @Autowired Ledger ledger;
    @Autowired Reconciliation reconciliation;
    @Autowired TransactionTemplate tx;
    @Autowired MovableClock clock;

    private String me;

    @BeforeEach
    void setUp() {
        me = trading.openAccount("me").id();
    }

    @Test
    void signupIsRecordedAsIssuanceAndReconciles() {
        trading.openAccount("other");

        assertThat(account(me).availablePoints()).isEqualTo(PointSource.SIGNUP.amount());
        Reconciliation.Report report = reconciliation.run();
        assertThat(report.balanced()).isTrue();
        assertThat(report.issuance().bySource()).containsEntry("SIGNUP", 2 * PointSource.SIGNUP.amount());
        assertThat(report.issuance().ledgerIssued()).isEqualTo(2 * PointSource.SIGNUP.amount());
    }

    @Test
    void sameRewardIsPaidOnce() {
        PointGrant first = issuer.grant(me, PointSource.AD_VIEW, "ad-tx-1");
        PointGrant retry = issuer.grant(me, PointSource.AD_VIEW, "ad-tx-1");

        assertThat(retry).isEqualTo(first);
        assertThat(account(me).availablePoints()).isEqualTo(PointSource.SIGNUP.amount() + PointSource.AD_VIEW.amount());
        assertThat(reconciliation.run().balanced()).isTrue();
    }

    @Test
    void rewardAlreadyPaidToAnotherAccountIsConflict() {
        String other = trading.openAccount("other").id();
        issuer.grant(me, PointSource.POST, "post-1");

        assertThatThrownBy(() -> issuer.grant(other, PointSource.POST, "post-1"))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(account(other).availablePoints()).isEqualTo(PointSource.SIGNUP.amount());
    }

    @Test
    void dailyLimitResetsAtMidnightKst() {
        for (int i = 0; i < PointSource.POST.dailyLimit(); i++) issuer.grant(me, PointSource.POST, "post-" + i);

        assertThatThrownBy(() -> issuer.grant(me, PointSource.POST, "post-over"))
                .isInstanceOf(TradingException.class);

        clock.now = LATE_NIGHT.plus(Duration.ofMinutes(31));
        issuer.grant(me, PointSource.POST, "post-over");
        assertThat(account(me).availablePoints())
                .isEqualTo(PointSource.SIGNUP.amount() + 4 * PointSource.POST.amount());
    }

    @Test
    void concurrentRewardsStayWithinDailyLimit() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            String ref = "ad-" + i;
            results.add(pool.submit(() -> {
                try {
                    issuer.grant(me, PointSource.AD_VIEW, ref);
                    return true;
                } catch (TradingException e) {
                    return false;
                }
            }));
        }
        int paid = 0;
        for (Future<Boolean> f : results) if (f.get()) paid++;
        pool.shutdown();

        assertThat(paid).isEqualTo(PointSource.AD_VIEW.dailyLimit());
        assertThat(account(me).availablePoints())
                .isEqualTo(PointSource.SIGNUP.amount() + paid * PointSource.AD_VIEW.amount());
        assertThat(reconciliation.run().balanced()).isTrue();
    }

    @Test
    void rewardsDoNotCountAsInvestmentReturn() {
        issuer.grant(me, PointSource.AD_VIEW, "ad-1");
        issuer.grant(me, PointSource.POST, "post-1");

        Account a = account(me);
        assertThat(trading.totalValue(a)).isEqualTo(a.initialValue());
    }

    @Test
    void reconciliationFindsPointsIssuedWithoutRecord() {
        tx.executeWithoutResult(s -> {
            ledger.post(Journal.issue(me, 5_000), clock.instant());
            accounts.applyPoints(me, 5_000, 0);
        });

        Reconciliation.Report report = reconciliation.run();
        assertThat(report.mismatches()).isEmpty();
        assertThat(report.issuance().matches()).isFalse();
        assertThat(report.balanced()).isFalse();
    }

    private Account account(String id) {
        return accounts.find(id).orElseThrow();
    }
}
