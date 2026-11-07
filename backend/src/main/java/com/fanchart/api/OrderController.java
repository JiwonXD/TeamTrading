package com.fanchart.api;

import com.fanchart.api.ApiDtos.PlaceOrderRequest;
import com.fanchart.trading.OrderReceipt;
import com.fanchart.trading.TradingService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
class OrderController {

    private final TradingService trading;

    OrderController(TradingService trading) {
        this.trading = trading;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    OrderReceipt place(@AuthenticationPrincipal String accountId, @Valid @RequestBody PlaceOrderRequest req) {
        return trading.place(accountId, req.clientOrderId(), req.teamId(), req.side(), req.type(),
                req.price(), req.quantity());
    }

    @DeleteMapping("/{orderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel(@AuthenticationPrincipal String accountId, @PathVariable long orderId) {
        trading.cancel(accountId, orderId);
    }
}
