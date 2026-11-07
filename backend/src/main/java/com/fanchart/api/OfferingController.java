package com.fanchart.api;

import com.fanchart.offering.Offering;
import com.fanchart.offering.OfferingService;
import com.fanchart.offering.Subscription;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** 공모 청약. 공모 등록·즉시 배정·취소는 운영자만(SecurityConfig의 /api/admin/**) */
@RestController
class OfferingController {

    record OfferingResponse(long id, String teamId, String teamName, int round, boolean firstListing, long price, long totalShares,
                            long maxPerAccount, Instant subscribeFrom, Instant subscribeTo, Instant listingAt,
                            String status, long allocatedShares, long subscribers, long requested) {
    }

    record CreateRequest(@NotBlank String teamId, @Min(1) Long price, @NotNull @Min(1) Long totalShares,
                         @NotNull @Min(1) Long maxPerAccount, @NotNull Instant subscribeFrom,
                         @NotNull Instant subscribeTo, @NotNull Instant listingAt) {
    }

    /** 내 청약. 계정 id는 내보내지 않는다 */
    record SubscriptionResponse(long offeringId, long quantity, long deposit, Long allocated, Instant createdAt) {
        static SubscriptionResponse of(Subscription s) {
            return new SubscriptionResponse(s.offeringId(), s.quantity(), s.deposit(), s.allocated(), s.createdAt());
        }
    }

    record SubscribeRequest(@NotNull @Min(1) Long quantity) {
    }

    private final OfferingService offerings;

    OfferingController(OfferingService offerings) {
        this.offerings = offerings;
    }

    @GetMapping("/api/offerings")
    List<OfferingResponse> recent(@RequestParam(defaultValue = "10") int limit) {
        return offerings.recent(Math.min(Math.max(limit, 1), 50)).stream().map(OfferingController::view).toList();
    }

    @GetMapping("/api/offerings/{offeringId}")
    OfferingResponse offering(@PathVariable long offeringId) {
        return view(offerings.view(offeringId));
    }

    @GetMapping("/api/teams/{teamId}/offerings")
    List<OfferingResponse> teamOfferings(@PathVariable String teamId) {
        return offerings.byTeam(teamId).stream().map(OfferingController::view).toList();
    }

    @GetMapping("/api/accounts/me/subscriptions")
    List<SubscriptionResponse> mine(@AuthenticationPrincipal String accountId,
                                    @RequestParam(defaultValue = "20") int limit) {
        return offerings.mine(accountId, Math.min(Math.max(limit, 1), 100)).stream().map(SubscriptionResponse::of).toList();
    }

    @PostMapping("/api/offerings/{offeringId}/subscriptions")
    SubscriptionResponse subscribe(@AuthenticationPrincipal String accountId, @PathVariable long offeringId,
                                   @Valid @RequestBody SubscribeRequest req) {
        return SubscriptionResponse.of(offerings.subscribe(offeringId, accountId, req.quantity()));
    }

    @DeleteMapping("/api/offerings/{offeringId}/subscriptions/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancelSubscription(@AuthenticationPrincipal String accountId, @PathVariable long offeringId) {
        offerings.cancelSubscription(offeringId, accountId);
    }

    @PostMapping("/api/admin/offerings")
    @ResponseStatus(HttpStatus.CREATED)
    OfferingResponse create(@Valid @RequestBody CreateRequest req) {
        Offering o = offerings.create(req.teamId(), req.price(), req.totalShares(), req.maxPerAccount(),
                req.subscribeFrom(), req.subscribeTo(), req.listingAt());
        return view(offerings.view(o.id()));
    }

    /** 청약 마감이 지났으면 스케줄러를 기다리지 않고 바로 배정 */
    @PostMapping("/api/admin/offerings/{offeringId}/allocate")
    OfferingResponse allocate(@PathVariable long offeringId) {
        offerings.allocate(offeringId);
        return view(offerings.view(offeringId));
    }

    @PostMapping("/api/admin/offerings/{offeringId}/cancel")
    OfferingResponse cancel(@PathVariable long offeringId) {
        offerings.cancel(offeringId);
        return view(offerings.view(offeringId));
    }

    private static OfferingResponse view(OfferingService.View v) {
        Offering o = v.offering();
        return new OfferingResponse(o.id(), o.teamId(), v.teamName(), o.round(), o.firstListing(), o.price(), o.totalShares(),
                o.maxPerAccount(), o.subscribeFrom(), o.subscribeTo(), o.listingAt(), o.status().name(),
                o.allocatedShares(), v.subscribers(), v.requested());
    }
}
