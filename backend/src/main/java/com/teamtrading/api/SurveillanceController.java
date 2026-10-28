package com.teamtrading.api;

import com.teamtrading.api.ApiDtos.AlertResponse;
import com.teamtrading.trading.TradingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 운영자용 탐지 기록. 권한 검사는 로그인 도입 때 붙인다 */
@RestController
class SurveillanceController {

    private final TradingService trading;

    SurveillanceController(TradingService trading) {
        this.trading = trading;
    }

    @GetMapping("/api/surveillance/alerts")
    List<AlertResponse> alerts(@RequestParam(defaultValue = "50") int limit) {
        return trading.alerts(Math.min(limit, 200)).stream().map(AlertResponse::of).toList();
    }
}
