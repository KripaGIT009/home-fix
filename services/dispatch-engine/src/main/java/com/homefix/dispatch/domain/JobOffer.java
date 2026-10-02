package com.homefix.dispatch.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One job offer made to one provider for one booking (Requirements 8.5-8.7), as the Dispatch
 * Engine stores it while it waits for the provider's answer.
 *
 * <p>The transition rules live here, as pure functions, so the store only has to apply them
 * atomically: a decision is honoured only from {@link OfferStatus#PENDING}, only for the provider
 * the offer was made to, and only before {@code expiresAt}. A decision arriving at or after the
 * deadline expires the offer instead, so a late accept can never win against the timeout.
 *
 * @param bookingId     the booking on offer
 * @param providerId    the provider it was offered to; equal to their auth user id (the JWT
 *                      subject), because provider-service pins a provider profile's id to its
 *                      owner's user id and reports that id as the eligible candidate's id
 * @param status        where the offer is in its lifecycle
 * @param offeredAt     when the offer was made
 * @param expiresAt     the end of the response window
 * @param decidedAt     when the offer left PENDING, or {@code null} while pending
 * @param emergency     whether the booking is an emergency
 * @param subcategoryId the booked service subcategory, when known
 * @param reference     the customer-facing booking reference, when the event carried it
 * @param scheduledAt   the booked slot, when the event carried it
 */
public record JobOffer(
        UUID bookingId,
        UUID providerId,
        OfferStatus status,
        Instant offeredAt,
        Instant expiresAt,
        Instant decidedAt,
        boolean emergency,
        UUID subcategoryId,
        String reference,
        Instant scheduledAt) {

    public JobOffer {
        Objects.requireNonNull(bookingId, "bookingId must not be null");
        Objects.requireNonNull(providerId, "providerId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(offeredAt, "offeredAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    /** A new pending offer whose window runs for {@code timeout} from {@code now}. */
    public static JobOffer pending(DispatchRequest request, UUID providerId, Instant now, Duration timeout) {
        return new JobOffer(request.bookingId(), providerId, OfferStatus.PENDING, now, now.plus(timeout),
                null, request.emergency(), request.subcategoryId(), request.reference(),
                request.scheduledAt());
    }

    /** A new pending offer carrying only the ids, for callers without the dispatch request. */
    public static JobOffer pending(UUID bookingId, UUID providerId, Instant now, Duration timeout) {
        return new JobOffer(bookingId, providerId, OfferStatus.PENDING, now, now.plus(timeout),
                null, false, null, null, null);
    }

    public boolean isOfferedTo(UUID candidate) {
        return providerId.equals(candidate);
    }

    /** True while the provider can still answer: pending and inside the window. */
    public boolean isOpenAt(Instant now) {
        return status == OfferStatus.PENDING && now.isBefore(expiresAt);
    }

    /** Whole seconds left in the window; 0 once it has closed or the offer was decided. */
    public long secondsRemaining(Instant now) {
        if (!isOpenAt(now)) {
            return 0;
        }
        return Math.max(0, Duration.between(now, expiresAt).toSeconds());
    }

    /** Length of the response window the offer was made with. */
    public long timeoutSeconds() {
        return Duration.between(offeredAt, expiresAt).toSeconds();
    }

    /**
     * Applies a provider's decision. Returns {@code this} unchanged when the caller is not the
     * offered provider or the offer has already left PENDING; returns the offer EXPIRED when the
     * window has closed; otherwise returns it in {@code decision}.
     *
     * @param decision {@link OfferStatus#ACCEPTED} or {@link OfferStatus#DECLINED}
     */
    public JobOffer decide(UUID caller, OfferStatus decision, Instant now) {
        if (decision != OfferStatus.ACCEPTED && decision != OfferStatus.DECLINED) {
            throw new IllegalArgumentException("a provider can only accept or decline, not " + decision);
        }
        if (!isOfferedTo(caller) || status != OfferStatus.PENDING) {
            return this;
        }
        if (!now.isBefore(expiresAt)) {
            return withStatus(OfferStatus.EXPIRED, now);
        }
        return withStatus(decision, now);
    }

    /**
     * Closes a still-pending offer to {@code providerId} as {@code terminal} (EXPIRED on timeout,
     * WITHDRAWN on cancellation). An offer that was already decided is returned unchanged, so a
     * decision that landed just before the close is kept.
     */
    public JobOffer close(UUID offeredProvider, OfferStatus terminal, Instant now) {
        if (terminal != OfferStatus.EXPIRED && terminal != OfferStatus.WITHDRAWN) {
            throw new IllegalArgumentException("an offer can only be closed as EXPIRED or WITHDRAWN");
        }
        if (status != OfferStatus.PENDING || (offeredProvider != null && !isOfferedTo(offeredProvider))) {
            return this;
        }
        return withStatus(terminal, now);
    }

    private JobOffer withStatus(OfferStatus next, Instant at) {
        return new JobOffer(bookingId, providerId, next, offeredAt, expiresAt, at, emergency,
                subcategoryId, reference, scheduledAt);
    }
}
