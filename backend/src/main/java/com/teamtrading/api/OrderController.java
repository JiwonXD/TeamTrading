package com.teamtrading.api;

import com.teamtrading.api.ApiDtos.PlaceOrderRequest;
import com.teamtrading.trading.OrderReceipt;
import com.teamtrading.trading.TradingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import static com.teamtrading.api.AccountController.ACCOUNT_HEADER;

@RestController
@RequestMapping("/api/orders")
class OrderController {

    private final TradingService trading;

    OrderController(TradingService trading) {
        this.trading = trading;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    OrderReceipt place(@RequestHeader(ACCOUNT_HEADER) String accountId, @Valid @RequestBody PlaceOrderRequest req) {
        return trading.place(accountId, req.clientOrderId(), req.teamId(), req.side(), req.type(),
                req.price(), req.quantity());
    }

    @DeleteMapping("/{orderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel(@RequestHeader(ACCOUNT_HEADER) String accountId, @PathVariable long orderId) {
        trading.cancel(accountId, orderId);
    }
}
