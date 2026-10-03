package com.homefix.booking.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.booking.api.dto.BookingResponse;
import com.homefix.booking.service.ProviderAssignmentService;

/**
 * The assigned Provider's answer to a Tenant assignment (Requirement MT-6), from the provider app's
 * job screens.
 *
 * <p>Like the other {@code POST /bookings/**} commands these have no role rule — any authenticated
 * caller reaches them — and ownership is the service's: only the booking's assigned Provider may
 * answer, everyone else gets 404 (Requirement MT-6.3).
 */
@RestController
@RequestMapping("/bookings/{bookingKey}/assignment")
public class ProviderAssignmentController {

    private final ProviderAssignmentService assignments;
    private final CallerIdentity callerIdentity;

    public ProviderAssignmentController(ProviderAssignmentService assignments, CallerIdentity callerIdentity) {
        this.assignments = assignments;
        this.callerIdentity = callerIdentity;
    }

    /** {@code POST /bookings/{bookingKey}/assignment/acceptance} — PROVIDER_ACCEPTED (MT-6.1). */
    @PostMapping("/acceptance")
    public ResponseEntity<BookingResponse> accept(@PathVariable("bookingKey") String bookingKey) {
        return ResponseEntity.ok(BookingResponse.of(
                assignments.accept(bookingKey, callerIdentity.requireCallerId())));
    }

    /** {@code POST /bookings/{bookingKey}/assignment/rejection} — back to the Tenant's queue (MT-6.2). */
    @PostMapping("/rejection")
    public ResponseEntity<BookingResponse> reject(@PathVariable("bookingKey") String bookingKey) {
        return ResponseEntity.ok(BookingResponse.of(
                assignments.decline(bookingKey, callerIdentity.requireCallerId())));
    }
}
