package com.homefix.booking.api;

import java.util.Set;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingRepository;
import com.homefix.booking.domain.BookingStatus;

/**
 * Service-to-service check behind saved-address deletion: the Customer Service refuses to delete
 * an address that an active booking still needs (Requirement 2.6).
 *
 * <p>Lives on the {@code /internal/**} surface for the same reasons as
 * {@link InternalDispatchController}: it is not routed through the API Gateway and is
 * authenticated by the shared internal credential enforced by {@code InternalApiKeyFilter}. The
 * Customer Service has already authorised the customer before asking.
 */
@RestController
@RequestMapping("/internal/bookings")
public class InternalAddressUsageController {

    /**
     * States in which a booking still needs its address: every state before the job is done, from
     * an unconfirmed booking to the wait for the customer's approval of an additional quote. That
     * includes the Tenant queue (AWAITING_ASSIGNMENT, PROVIDER_ASSIGNED), whose Tenant_Admins read
     * the address to pick a Provider and whose Provider then travels there. {@code CREATED} counts
     * because the booking can still be confirmed and dispatched to the address; it can be cancelled
     * at no fee, so it never blocks the delete for good. Once the job is completed nobody goes there
     * again; the payment and refund states after it would otherwise hold the address forever.
     */
    static final Set<BookingStatus> ADDRESS_IN_USE_STATUSES = Set.of(
            BookingStatus.CREATED,
            BookingStatus.SEARCHING_PROVIDER,
            BookingStatus.AWAITING_ASSIGNMENT,
            BookingStatus.PROVIDER_ASSIGNED,
            BookingStatus.PROVIDER_ACCEPTED,
            BookingStatus.PROVIDER_ON_THE_WAY,
            BookingStatus.PROVIDER_ARRIVED,
            BookingStatus.JOB_STARTED,
            BookingStatus.JOB_PAUSED,
            BookingStatus.ADDITIONAL_QUOTE_REQUIRED,
            BookingStatus.CUSTOMER_APPROVAL_PENDING);

    private final BookingRepository bookingRepository;

    public InternalAddressUsageController(BookingRepository bookingRepository) {
        this.bookingRepository = bookingRepository;
    }

    /**
     * {@code GET /internal/bookings/active?customerId=..&addressId=..} — the reference of one of
     * the customer's active bookings at that address, or a {@code null} reference when there is
     * none. Always 200, so the caller can tell "not in use" from a failed lookup.
     */
    @GetMapping("/active")
    @Transactional(readOnly = true)
    public ResponseEntity<ActiveBookingResponse> activeBookingAtAddress(
            @RequestParam("customerId") UUID customerId,
            @RequestParam("addressId") UUID addressId) {
        String reference = bookingRepository
                .findFirstByCustomerIdAndAddressIdAndStatusIn(customerId, addressId, ADDRESS_IN_USE_STATUSES)
                .map(Booking::getReference)
                .orElse(null);
        return ResponseEntity.ok(new ActiveBookingResponse(reference));
    }

    /** Response shape the Customer Service's {@code HttpBookingClientAdapter} reads. */
    public record ActiveBookingResponse(String bookingReference) {
    }
}
