package com.fanchart.api;

import com.fanchart.trading.MarketHours;
import com.fanchart.trading.TradingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 매일 점검시간. 화면이 이 시간에 매매 버튼을 막고 안내한다. 주문 거절은 서버가 한다 */
@RestController
class MarketHoursController {

    /** 한국 시간 HH:mm. 점검시간 설정이 꺼져 있으면 maintenance가 null이다 */
    record MarketHoursResponse(Window maintenance) {
    }

    record Window(String start, String end, String timeZone) {
    }

    private final TradingService trading;

    MarketHoursController(TradingService trading) {
        this.trading = trading;
    }

    @GetMapping("/api/market-hours")
    MarketHoursResponse hours() {
        if (!trading.maintenanceEnabled()) return new MarketHoursResponse(null);
        return new MarketHoursResponse(new Window(MarketHours.MAINTENANCE_START.toString(),
                MarketHours.MAINTENANCE_END.toString(), MarketHours.ZONE.getId()));
    }
}
