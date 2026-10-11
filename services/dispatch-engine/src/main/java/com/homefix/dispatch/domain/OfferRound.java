package com.homefix.dispatch.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Every offer made for one booking (Requirements 8.5-8.7). Dispatch offers a booking to several
 * providers at once, in waves, so the offers are kept together and changed as one value: that is
 * what makes "the first provider to accept wins" atomic. Accepting withdraws every other offer
 * still pending in the same transition, so a second provider can never accept the same job.
 *
 * <p>Like {@link JobOffer}, the transitions are pure functions; the store applies them under a
 * compare-and-set. Offers are only ever added or changed, never removed, and at most one offer per
 * provider is kept: re-offering a provider replaces their earlier (terminal) offer.
 *
 * @param bookingId the booking on offer
 * @param offers    the offers in the order they were made
 */
public record OfferRound(UUID bookingId, List<JobOffer> offers) {

    public OfferRound {
        Objects.requireNonNull(bookingId, "bookingId must not be null");
        offers = List.copyOf(offers);
        for (JobOffer offer : offers) {
            if (!offer.bookingId().equals(bookingId)) {
                throw new IllegalArgumentException("offer for booking " + offer.bookingId()
                        + " does not belong to the round of booking " + bookingId);
            }
        }
    }

    /** A round with no offers yet. */
    public static OfferRound empty(UUID bookingId) {
        return new OfferRound(bookingId, List.of());
    }

    public boolean isEmpty() {
        return offers.isEmpty();
    }

    /** The offer made to {@code providerId}, if any. */
    public Optional<JobOffer> offerTo(UUID providerId) {
        return offers.stream().filter(o -> o.isOfferedTo(providerId)).findFirst();
    }

    /** The accepted offer, once a provider has accepted. */
    public Optional<JobOffer> accepted() {
        return offers.stream().filter(o -> o.status() == OfferStatus.ACCEPTED).findFirst();
    }

    /**
     * Adds a wave of PENDING offers, replacing any earlier offer to the same providers. Once a
     * provider has accepted the booking is taken, and the round is returned unchanged.
     */
    public OfferRound open(List<JobOffer> wave) {
        if (accepted().isPresent()) {
            return this;
        }
        Map<UUID, JobOffer> byProvider = new LinkedHashMap<>();
        offers.forEach(o -> byProvider.put(o.providerId(), o));
        wave.forEach(o -> byProvider.put(o.providerId(), o));
        return new OfferRound(bookingId, new ArrayList<>(byProvider.values()));
    }

    /**
     * Applies a provider's decision to their own offer (see {@link JobOffer#decide}). An accept
     * that is honoured also withdraws every other pending offer, so it is the only acceptance the
     * round will ever hold. Returns {@code this} unchanged when the caller has no offer here or
     * their offer has already left PENDING.
     *
     * @param decision {@link OfferStatus#ACCEPTED} or {@link OfferStatus#DECLINED}
     */
    public OfferRound decide(UUID caller, OfferStatus decision, Instant now) {
        Optional<JobOffer> mine = offerTo(caller);
        if (mine.isEmpty()) {
            return this;
        }
        JobOffer decided = mine.get().decide(caller, decision, now);
        if (decided.equals(mine.get())) {
            return this;
        }
        if (decided.status() == OfferStatus.ACCEPTED && accepted().isPresent()) {
            // Cannot happen while accepting withdraws the rest; kept so a round can never hold two.
            return this;
        }
        List<JobOffer> next = new ArrayList<>(offers.size());
        for (JobOffer offer : offers) {
            if (offer.isOfferedTo(caller)) {
                next.add(decided);
            } else if (decided.status() == OfferStatus.ACCEPTED) {
                next.add(offer.close(offer.providerId(), OfferStatus.WITHDRAWN, now));
            } else {
                next.add(offer);
            }
        }
        return new OfferRound(bookingId, next);
    }

    /**
     * Closes the still-pending offers to {@code providers} — or to everyone when {@code providers}
     * is {@code null} — as {@code terminal} (EXPIRED when a wave's window ends, WITHDRAWN when the
     * booking is cancelled). Offers already decided keep their decision.
     */
    public OfferRound close(Collection<UUID> providers, OfferStatus terminal, Instant now) {
        List<JobOffer> next = new ArrayList<>(offers.size());
        for (JobOffer offer : offers) {
            if (providers == null || providers.contains(offer.providerId())) {
                next.add(offer.close(offer.providerId(), terminal, now));
            } else {
                next.add(offer);
            }
        }
        return new OfferRound(bookingId, next);
    }
}
