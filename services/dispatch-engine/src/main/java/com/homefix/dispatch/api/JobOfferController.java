package com.homefix.dispatch.api;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.homefix.dispatch.domain.OfferStatus;
import com.homefix.dispatch.service.JobOfferService;
import com.homefix.dispatch.service.JobOfferService.Decision;
import com.homefix.shared.observability.error.ErrorResponseDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provider-facing job-offer endpoints (Requirements 8.5-8.7, 28.8). The provider app polls the
 * list, opens an offer, and accepts or declines it here; the dispatch thread waiting on that offer
 * picks the decision up from the shared store.
 *
 * <ul>
 *   <li>{@code GET  /dispatch/offers} — the caller's open offers, soonest-expiring first.</li>
 *   <li>{@code GET  /dispatch/offers/{bookingId}} — one offer, in any status.</li>
 *   <li>{@code POST /dispatch/offers/{bookingId}/accept} and {@code /decline} — the decision;
 *       200 with the decided offer, 409 {@code OFFER_EXPIRED} after the window, 409
 *       {@code OFFER_ALREADY_DECIDED} for a second decision.</li>
 * </ul>
 *
 * <p>The SERVICE_PROVIDER role gate is the shared {@code RbacEnforcementFilter}'s (see
 * {@code DispatchRbacConfig}); ownership is enforced here. An offer is visible only to the provider
 * it was made to, and anyone else gets the same 404 as for a booking with no offer, so offers cannot
 * be probed by id.
 */
@RestController
@RequestMapping("/dispatch/offers")
public class JobOfferController {

    private final JobOfferService offers;
    private final CallerIdentity callerIdentity;
    private final Clock clock;

    public JobOfferController(JobOfferService offers, CallerIdentity callerIdentity, Clock clock) {
        this.offers = offers;
        this.callerIdentity = callerIdentity;
        this.clock = clock;
    }

    @GetMapping
    public List<JobOfferResponse> pending() {
        UUID caller = callerIdentity.requireCallerId();
        var now = clock.instant();
        return offers.pendingFor(caller).stream()
                .map(offer -> JobOfferResponse.of(offer, now))
                .toList();
    }

    @GetMapping("/{bookingId}")
    public JobOfferResponse detail(@PathVariable UUID bookingId) {
        UUID caller = callerIdentity.requireCallerId();
        return offers.viewFor(bookingId, caller)
                .map(offer -> JobOfferResponse.of(offer, clock.instant()))
                .orElseThrow(JobOfferController::notFound);
    }

    @PostMapping("/{bookingId}/accept")
    public JobOfferResponse accept(@PathVariable UUID bookingId) {
        return decide(bookingId, OfferStatus.ACCEPTED);
    }

    @PostMapping("/{bookingId}/decline")
    public JobOfferResponse decline(@PathVariable UUID bookingId) {
        return decide(bookingId, OfferStatus.DECLINED);
    }

    private JobOfferResponse decide(UUID bookingId, OfferStatus decision) {
        UUID caller = callerIdentity.requireCallerId();
        Decision result = offers.decide(bookingId, caller, decision);
        return switch (result.outcome()) {
            case APPLIED -> JobOfferResponse.of(result.offer(), clock.instant());
            case NOT_FOUND -> throw notFound();
            case EXPIRED -> throw new OfferApiException(HttpStatus.CONFLICT, "OFFER_EXPIRED",
                    "This job offer has expired");
            case ALREADY_DECIDED -> throw new OfferApiException(HttpStatus.CONFLICT,
                    "OFFER_ALREADY_DECIDED", "This job offer is already "
                    + result.offer().status().name().toLowerCase());
        };
    }

    private static OfferApiException notFound() {
        return new OfferApiException(HttpStatus.NOT_FOUND, "OFFER_NOT_FOUND",
                "No job offer for this booking is waiting for you");
    }

    @ExceptionHandler(OfferApiException.class)
    public ResponseEntity<ErrorResponseDto> onOfferApiException(OfferApiException ex) {
        ErrorResponseDto body = ErrorResponseDto.builder()
                .errorCode(ex.errorCode())
                .message(ex.getMessage())
                .build();
        return ResponseEntity.status(ex.status()).body(body);
    }
}
