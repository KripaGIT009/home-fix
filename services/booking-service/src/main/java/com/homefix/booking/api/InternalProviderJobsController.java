package com.homefix.booking.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.booking.api.dto.ProviderJobResponse;
import com.homefix.booking.domain.BookingRepository;
import com.homefix.booking.domain.BookingStatus;

/**
 * Service-to-service read endpoint backing the provider dashboard's active-job list
 * (Requirement 28.8).
 *
 * <p>Lives on the {@code /internal/**} surface for the same reasons as
 * {@link InternalDispatchController}: it is not routed through the API Gateway, carries no
 * end-user token, and is authenticated by the shared internal credential enforced by
 * {@code InternalApiKeyFilter}. Because it is not user-authenticated, it performs no ownership
 * check of its own — the Provider Service resolves and authorises the provider before calling,
 * and only ever asks for the caller's own id.
 */
@RestController
@RequestMapping("/internal/bookings")
public class InternalProviderJobsController {

    /**
     * States in which a job counts as "active" for the provider: from the moment dispatch puts it
     * on their plate until payment settles it. {@code CREATED} and {@code SEARCHING_*} precede
     * assignment and belong to nobody yet; {@code PAYMENT_COMPLETED}, {@code CANCELLED},
     * {@code REFUNDED} and {@code DISPUTED} are terminal or handled elsewhere.
     */
    private static final Set<BookingStatus> ACTIVE_STATUSES = Set.of(
            BookingStatus.PROVIDER_ASSIGNED,
            BookingStatus.PROVIDER_ACCEPTED,
            BookingStatus.PROVIDER_ON_THE_WAY,
            BookingStatus.PROVIDER_ARRIVED,
            BookingStatus.JOB_STARTED,
            BookingStatus.JOB_PAUSED,
            BookingStatus.ADDITIONAL_QUOTE_REQUIRED,
            BookingStatus.CUSTOMER_APPROVAL_PENDING,
            BookingStatus.JOB_COMPLETED,
            BookingStatus.CUSTOMER_CONFIRMED,
            BookingStatus.PAYMENT_PENDING);

    private final BookingRepository bookingRepository;

    public InternalProviderJobsController(BookingRepository bookingRepository) {
        this.bookingRepository = bookingRepository;
    }

    /**
     * {@code GET /internal/bookings/provider/{providerId}/active} — the provider's in-flight jobs,
     * soonest scheduled first. Returns an empty list rather than 404 when there are none, so the
     * caller can render an empty dashboard without treating it as an error.
     */
    @GetMapping("/provider/{providerId}/active")
    @Transactional(readOnly = true)
    public ResponseEntity<List<ProviderJobResponse>> activeJobs(@PathVariable("providerId") UUID providerId) {
        return ResponseEntity.ok(
                bookingRepository.findByProviderIdAndStatusInOrderByScheduledAtAsc(providerId, ACTIVE_STATUSES)
                        .stream()
                        .map(ProviderJobResponse::of)
                        .toList());
    }
}
