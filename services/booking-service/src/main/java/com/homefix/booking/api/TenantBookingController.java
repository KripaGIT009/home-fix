package com.homefix.booking.api;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.booking.api.dto.AssignBookingRequest;
import com.homefix.booking.api.dto.TenantBookingResponse;
import com.homefix.booking.domain.BookingStatus;
import com.homefix.booking.service.TenantBookingService;

import jakarta.validation.Valid;

/**
 * The Tenant Portal's booking endpoints (Requirements MT-5, MT-8.3), reached through the gateway's
 * {@code /tenant/bookings/**} route.
 *
 * <p>{@code BookingRbacConfig} admits only {@code TENANT_ADMIN} here. Nothing on this path takes a
 * Tenant id: {@link TenantBookingService} resolves the caller's Tenant from the JWT subject and
 * scopes every read and the assignment to it (Requirement MT-10.1, MT-10.2).
 */
@RestController
@RequestMapping("/tenant/bookings")
public class TenantBookingController {

    private final TenantBookingService tenantBookings;
    private final CallerIdentity callerIdentity;

    public TenantBookingController(TenantBookingService tenantBookings, CallerIdentity callerIdentity) {
        this.tenantBookings = tenantBookings;
        this.callerIdentity = callerIdentity;
    }

    /**
     * {@code GET /tenant/bookings/queue} — the caller's Tenant's Assignment_Queue, oldest queued
     * first (Requirement MT-5.1). The portal polls it.
     */
    @GetMapping("/queue")
    public ResponseEntity<List<TenantBookingResponse>> queue() {
        return ResponseEntity.ok(tenantBookings.queue(callerIdentity.requireCallerId()).stream()
                .map(TenantBookingResponse::of)
                .toList());
    }

    /**
     * {@code GET /tenant/bookings?status=} — the caller's Tenant's bookings, newest first, at most
     * {@value TenantBookingService#LIST_LIMIT}. An unknown {@code status} is a 400
     * {@code VALIDATION_ERROR}.
     */
    @GetMapping
    public ResponseEntity<List<TenantBookingResponse>> bookings(
            @RequestParam(value = "status", required = false) BookingStatus status) {
        return ResponseEntity.ok(tenantBookings.bookings(callerIdentity.requireCallerId(), status).stream()
                .map(TenantBookingResponse::of)
                .toList());
    }

    /**
     * {@code POST /tenant/bookings/{bookingKey}/assignment} — assign a queued booking (UUID or
     * reference) to one of the Tenant's Providers (Requirement MT-5.2 to MT-5.5). 409
     * {@code BOOKING_NOT_ASSIGNABLE} when another assignment won or the booking moved on; 409
     * {@code PROVIDER_NOT_ASSIGNABLE} for a Provider who is not an approved member.
     */
    @PostMapping("/{bookingKey}/assignment")
    public ResponseEntity<TenantBookingResponse> assign(@PathVariable("bookingKey") String bookingKey,
                                                        @Valid @RequestBody AssignBookingRequest request) {
        return ResponseEntity.ok(TenantBookingResponse.of(tenantBookings.assign(
                callerIdentity.requireCallerId(), bookingKey, request.providerId())));
    }
}
