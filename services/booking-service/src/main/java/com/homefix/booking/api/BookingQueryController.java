package com.homefix.booking.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.booking.api.dto.BookingDetailResponse;
import com.homefix.booking.api.dto.BookingHistoryPageResponse;
import com.homefix.booking.service.BookingQueryService;

/**
 * Customer-facing booking reads: service history and booking detail (Requirement 28.7).
 *
 * <p>Kept apart from {@link BookingController}, which holds the commands, so the read side's
 * ownership rules live in one place. Role gating is {@code BookingRbacConfig}'s; whether the
 * caller may see a particular booking is decided by {@link BookingQueryService} from the
 * {@link CallerIdentity} facts, and answered with 404 rather than 403 when they may not.
 */
@RestController
@RequestMapping("/bookings")
public class BookingQueryController {

    private final BookingQueryService queryService;
    private final CallerIdentity callerIdentity;

    public BookingQueryController(BookingQueryService queryService, CallerIdentity callerIdentity) {
        this.queryService = queryService;
        this.callerIdentity = callerIdentity;
    }

    /**
     * {@code GET /bookings/history?page&pageSize} — the caller's own bookings, newest first.
     * {@code page} is 1-based (default 1); {@code pageSize} is 1..50 (default 10). Anything else,
     * including a non-numeric value, is a 400 {@code VALIDATION_ERROR}.
     *
     * <p>Mapped as a literal path, so Spring prefers it over {@link #detail}'s
     * {@code /{bookingKey}} template; "history" is never looked up as a booking.
     */
    @GetMapping("/history")
    public ResponseEntity<BookingHistoryPageResponse> history(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize",
                    defaultValue = "" + BookingQueryService.DEFAULT_PAGE_SIZE) int pageSize) {
        return ResponseEntity.ok(BookingHistoryPageResponse.of(
                queryService.history(callerIdentity.requireCallerId(), page, pageSize)));
    }

    /**
     * {@code GET /bookings/{bookingKey}} — one booking, by UUID or by reference. Visible to its
     * customer, its assigned provider, and staff; 404 {@code BOOKING_NOT_FOUND} for anyone else.
     */
    @GetMapping("/{bookingKey}")
    public ResponseEntity<BookingDetailResponse> detail(@PathVariable("bookingKey") String bookingKey) {
        return ResponseEntity.ok(BookingDetailResponse.of(queryService.detail(
                bookingKey, callerIdentity.requireCallerId(), callerIdentity.isStaff())));
    }
}
