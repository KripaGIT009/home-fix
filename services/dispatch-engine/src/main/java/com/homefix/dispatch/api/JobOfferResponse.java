package com.homefix.dispatch.api;

import java.time.Instant;
import java.util.UUID;

import com.homefix.dispatch.domain.JobOffer;
import com.homefix.dispatch.domain.OfferStatus;

/**
 * A job offer as the provider app sees it (Requirement 28.8).
 *
 * <p>The Dispatch Engine knows the booking only as far as dispatch needs it, so this carries the
 * offer's own facts plus what {@code BookingCreated} supplied; the full job detail (address,
 * description, media) comes from the Booking Service once the provider has accepted.
 *
 * @param bookingId        the booking on offer
 * @param status           PENDING, ACCEPTED, DECLINED, EXPIRED or WITHDRAWN
 * @param reference        the customer-facing booking reference, or null if unknown
 * @param emergency        whether the booking is an emergency
 * @param subcategoryId    the booked service subcategory, or null if unknown
 * @param scheduledAt      the booked slot, or null if unknown
 * @param offeredAt        when the offer was made
 * @param expiresAt        when the response window closes
 * @param expiresInSeconds whole seconds left to respond, as of the server's clock; 0 once the window
 *                         has closed or the offer has been decided
 * @param timeoutSeconds   the full length of the response window
 */
public record JobOfferResponse(
        UUID bookingId,
        OfferStatus status,
        String reference,
        boolean emergency,
        UUID subcategoryId,
        Instant scheduledAt,
        Instant offeredAt,
        Instant expiresAt,
        long expiresInSeconds,
        long timeoutSeconds) {

    public static JobOfferResponse of(JobOffer offer, Instant now) {
        return new JobOfferResponse(offer.bookingId(), offer.status(), offer.reference(),
                offer.emergency(), offer.subcategoryId(), offer.scheduledAt(), offer.offeredAt(),
                offer.expiresAt(), offer.secondsRemaining(now), offer.timeoutSeconds());
    }
}
