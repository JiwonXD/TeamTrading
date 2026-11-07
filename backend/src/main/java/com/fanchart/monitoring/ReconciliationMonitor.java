package com.fanchart.monitoring;

import com.fanchart.ledger.Reconciliation;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 주기 대사. 원장·잔고·발행 기록을 맞춰 보고 결과를 지표로 남긴다.
 * fanchart.reconciliation.balanced가 0이 되면 경보를 울리도록 Prometheus 규칙을 둔다(docs/operations.md).
 * 어긋나면 어긋난 칸과 전표를 ERROR 로그로 남긴다
 */
@Component
@ConditionalOnProperty(name = "fanchart.monitoring.reconcile", havingValue = "true", matchIfMissing = true)
class ReconciliationMonitor {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationMonitor.class);

    private final Reconciliation reconciliation;
    private final Timer duration;
    private final AtomicInteger balanced = new AtomicInteger(1);
    private final AtomicInteger mismatches = new AtomicInteger();
    private final AtomicLong lastRun = new AtomicLong();

    ReconciliationMonitor(Reconciliation reconciliation, MeterRegistry registry, Clock clock) {
        this.reconciliation = reconciliation;
        // 첫 대사 전에는 기동 시각을 둔다. 대사가 한 번도 돌지 않으면 그때부터 늦어진 것으로 본다
        this.lastRun.set(clock.instant().getEpochSecond());
        this.duration = Timer.builder("fanchart.reconciliation.duration").description("대사 소요 시간").register(registry);
        Gauge.builder("fanchart.reconciliation.balanced", balanced, AtomicInteger::get)
                .description("마지막 대사가 맞았으면 1, 어긋났으면 0").register(registry);
        Gauge.builder("fanchart.reconciliation.mismatches", mismatches, AtomicInteger::get)
                .description("마지막 대사에서 어긋난 칸·전표 수").register(registry);
        Gauge.builder("fanchart.reconciliation.last.run", lastRun, AtomicLong::get)
                .description("마지막 대사 시각(epoch 초). 첫 대사 전에는 기동 시각").register(registry);
    }

    @Scheduled(fixedDelayString = "${fanchart.monitoring.reconcile-every:PT5M}", initialDelayString = "PT1M")
    void run() {
        try {
            Reconciliation.Report report = duration.record(reconciliation::run);
            balanced.set(report.balanced() ? 1 : 0);
            mismatches.set(report.mismatches().size() + report.unbalancedJournals().size()
                    + (report.issuance().matches() ? 0 : 1));
            lastRun.set(report.checkedAt().getEpochSecond());
            if (!report.balanced())
                log.error("reconciliation failed: mismatches={} unbalancedJournals={} issuance={}",
                        report.mismatches(), report.unbalancedJournals(), report.issuance());
        } catch (RuntimeException e) {
            balanced.set(0);
            log.error("reconciliation could not run", e);
        }
    }
}
