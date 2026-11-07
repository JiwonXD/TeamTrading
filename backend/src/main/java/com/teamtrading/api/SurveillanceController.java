package com.teamtrading.api;

import com.teamtrading.api.ApiDtos.AlertResponse;
import com.teamtrading.ledger.Reconciliation;
import com.teamtrading.trading.TradingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 탐지 기록은 닉네임만 보여 누구나 볼 수 있고, 계정 id가 나오는 대사는 운영자만 본다(SecurityConfig) */
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
