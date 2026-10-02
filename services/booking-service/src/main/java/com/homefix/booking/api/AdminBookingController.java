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

import com.homefix.booking.api.dto.AdminBookingResponse;
import com.homefix.booking.api.dto.AdminCancelBookingRequest;
import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingStatus;
import com.homefix.booking.service.BookingQueryService;
import com.homefix.booking.service.BookingService;

import jakarta.validation.Valid;

/**
 * The Admin Portal's Booking Management endpoints (Requirement 19.2), reached through the gateway's
 * {@code /admin/bookings} route unchanged.
 *
 * <p>Only staff get here: {@code BookingRbacConfig} restricts {@code /admin/bookings/**} to the
 * admin, support and dispatch roles. Nothing on this path has rules of its own — the list is the
 * read side's {@link BookingQueryService}, and the force-cancel is {@link BookingService#cancel}
 * with the caller as a staff actor, so the state machine (409 from a state that cannot be
 * cancelled), the cancellation-fee policy, the audit entry and the {@code BookingCancelled}
 * outbox event are exactly those of any other cancellation.
 */
@RestController
@RequestMapping("/admin/bookings")
public class AdminBookingController {

    private final BookingQueryService queryService;
    private final BookingService bookingService;
    private final CallerIdentity callerIdentity;

    public AdminBookingController(BookingQueryService queryService, BookingService bookingService,
                                  CallerIdentity callerIdentity) {
        this.queryService = queryService;
        this.bookingService = bookingService;
        this.callerIdentity = callerIdentity;
    }

    /**
     * {@code GET /admin/bookings?search&status} — bookings across all customers, newest first, at
     * most {@value BookingQueryService#ADMIN_LIST_LIMIT}. {@code search} matches the reference
     * (case-insensitive substring) or a booking UUID exactly; an unknown {@code status} is a 400
     * {@code VALIDATION_ERROR}.
     */
    @GetMapping
    public ResponseEntity<List<AdminBookingResponse>> list(
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "status", required = false) BookingStatus status) {
        return ResponseEntity.ok(queryService.adminSearch(search, status).stream()
                .map(AdminBookingResponse::of)
                .toList());
    }

    /**
     * {@code POST /admin/bookings/{id}/cancel} — force-cancel a booking, by UUID or reference,
     * with a required reason. Answers the cancelled booking as a list row so the portal can update
     * the table in place.
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<AdminBookingResponse> cancel(@PathVariable("id") String id,
                                                       @Valid @RequestBody AdminCancelBookingRequest request) {
        Booking booking = bookingService.cancel(id, callerIdentity.requireStaffActor(), request.reason());
        return ResponseEntity.ok(AdminBookingResponse.of(queryService.labelled(booking)));
    }
}
