package com.homefix.booking.api;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.booking.api.dto.BookingResponse;
import com.homefix.booking.api.dto.ProviderAcceptedRequest;
import com.homefix.booking.service.DispatchOutcomeService;

import jakarta.validation.Valid;

/**
 * Service-to-service endpoints the Dispatch Engine calls to record the outcome of a matching run
 * (Requirements 8.6, 8.9).
 *
 * <p>These are deliberately not part of the customer or provider API surface. They are not routed
 * through the API Gateway, they carry no end-user token, and they are authenticated by a shared
 * internal credential instead; see {@code InternalApiKeyFilter}. The Dispatch Engine had been calling
 * exactly these two paths since it was written, but no handler existed, so every accepted offer ended
 * in a transition failure and no booking ever reached PROVIDER_ACCEPTED.
 *
 * <p>Addressed by booking id rather than reference because the Dispatch Engine only ever sees the id,
 * which is what the {@code BookingCreated} event carries.
 */
@RestController
@RequestMapping("/internal/bookings")
public class InternalDispatchController {

    private final DispatchOutcomeService dispatchOutcomeService;

    public InternalDispatchController(DispatchOutcomeService dispatchOutcomeService) {
        this.dispatchOutcomeService = dispatchOutcomeService;
    }

    /**
     * {@code POST /internal/bookings/{bookingId}/provider-accepted} — a provider took the job.
     *
     * <p>Safe to retry: repeating the call for the same provider returns the same result rather than
     * a conflict, which matters because the caller wraps this in a retrying resilience stack.
     */
    @PostMapping("/{bookingId}/provider-accepted")
    public ResponseEntity<BookingResponse> providerAccepted(
            @PathVariable UUID bookingId,
            @Valid @RequestBody ProviderAcceptedRequest request) {
        return ResponseEntity.ok(BookingResponse.of(
                dispatchOutcomeService.markProviderAccepted(bookingId, request.providerId())));
    }

    /**
     * {@code POST /internal/bookings/{bookingId}/searching-failed} — dispatch found nobody.
     *
     * <p>Takes no body and is likewise safe to retry.
     */
    @PostMapping("/{bookingId}/searching-failed")
    public ResponseEntity<BookingResponse> searchingFailed(@PathVariable UUID bookingId) {
        return ResponseEntity.ok(BookingResponse.of(
                dispatchOutcomeService.markSearchingFailed(bookingId)));
    }
}
