package com.fanchart.api;

import com.fanchart.api.ApiDtos.AlertResponse;
import com.fanchart.ledger.Reconciliation;
import com.fanchart.trading.TradingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 시장감시는 운영자만 본다(SecurityConfig). 탐지 기록은 닉네임만 담지만, 일반 유저에게 공개하면 혐의가 확정되지 않은
 * 계정을 공개 지목하고 탐지 기준을 알려주게 된다(ADR 0026). 대사 결과는 계정 id가 나온다.
 */
@RestController
class SurveillanceController {

    private final TradingService trading;
    private final Reconciliation reconciliation;

    SurveillanceController(TradingService trading, Reconciliation reconciliation) {
        this.trading = trading;
        this.reconciliation = reconciliation;
    }

    @GetMapping("/api/surveillance/alerts")
    List<AlertResponse> alerts(@RequestParam(defaultValue = "50") int limit) {
        return trading.alerts(Math.min(limit, 200)).stream()
                .map(a -> AlertResponse.of(a, id -> trading.account(id).nickname())).toList();
    }

    @GetMapping("/api/surveillance/reconciliation")
    Reconciliation.Report reconcile() {
        return reconciliation.run();
    }
}
