package com.homefix.dispatch.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * The job-offer state machine as pure functions (Requirements 8.5-8.7): only the offered provider
 * can decide, only from PENDING, and only strictly before the deadline; a decision at or after the
 * deadline expires the offer instead; closing never overwrites a decision.
 */
class JobOfferTest {

    private static final Instant T0 = Instant.parse("2026-10-02T10:00:00Z");
    private static final Duration WINDOW = Duration.ofSeconds(60);

    private final UUID bookingId = UUID.randomUUID();
    private final UUID provider = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();

    private JobOffer pending() {
        return JobOffer.pending(bookingId, provider, T0, WINDOW);
    }

    @Test
    void pendingFromRequest_carriesTheDisplayFields() {
        UUID subcategory = UUID.randomUUID();
        Instant slot = T0.plus(Duration.ofHours(3));
        DispatchRequest request = new DispatchRequest(bookingId, UUID.randomUUID(), 12.9, 77.6,
                subcategory, List.of("plumbing"), true, T0, "HFX-2026-0000001", slot);

        JobOffer offer = JobOffer.pending(request, provider, T0, WINDOW);

        assertThat(offer.status()).isEqualTo(OfferStatus.PENDING);
        assertThat(offer.emergency()).isTrue();
        assertThat(offer.subcategoryId()).isEqualTo(subcategory);
        assertThat(offer.reference()).isEqualTo("HFX-2026-0000001");
        assertThat(offer.scheduledAt()).isEqualTo(slot);
        assertThat(offer.expiresAt()).isEqualTo(T0.plus(WINDOW));
        assertThat(offer.decidedAt()).isNull();
        assertThat(offer.timeoutSeconds()).isEqualTo(60);
    }

    @Test
    void pendingFromIds_leavesTheDisplayFieldsEmpty() {
        JobOffer offer = pending();

        assertThat(offer.emergency()).isFalse();
        assertThat(offer.subcategoryId()).isNull();
        assertThat(offer.reference()).isNull();
        assertThat(offer.scheduledAt()).isNull();
    }

    @Test
    void requiredFieldsAreEnforced() {
        assertThatThrownBy(() -> new JobOffer(null, provider, OfferStatus.PENDING, T0, T0, null,
                false, null, null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new JobOffer(bookingId, provider, null, T0, T0, null,
                false, null, null, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void isOpenOnlyWhilePendingAndBeforeTheDeadline() {
        JobOffer offer = pending();

        assertThat(offer.isOpenAt(T0)).isTrue();
        assertThat(offer.isOpenAt(T0.plus(WINDOW).minusMillis(1))).isTrue();
        assertThat(offer.isOpenAt(T0.plus(WINDOW))).isFalse();
        assertThat(offer.decide(provider, OfferStatus.DECLINED, T0).isOpenAt(T0)).isFalse();
    }

    @Test
    void secondsRemaining_countsDownAndFloorsAtZero() {
        JobOffer offer = pending();

        assertThat(offer.secondsRemaining(T0)).isEqualTo(60);
        assertThat(offer.secondsRemaining(T0.plusMillis(1_500))).isEqualTo(58);
        assertThat(offer.secondsRemaining(T0.plus(WINDOW))).isZero();
        assertThat(offer.secondsRemaining(T0.plus(Duration.ofMinutes(5)))).isZero();
        assertThat(offer.decide(provider, OfferStatus.ACCEPTED, T0).secondsRemaining(T0)).isZero();
    }

    @Test
    void offeredProviderAcceptsInsideTheWindow() {
        Instant at = T0.plusSeconds(10);

        JobOffer accepted = pending().decide(provider, OfferStatus.ACCEPTED, at);

        assertThat(accepted.status()).isEqualTo(OfferStatus.ACCEPTED);
        assertThat(accepted.decidedAt()).isEqualTo(at);
        assertThat(accepted.bookingId()).isEqualTo(bookingId);
        assertThat(accepted.expiresAt()).isEqualTo(T0.plus(WINDOW));
    }

    @Test
    void offeredProviderDeclinesInsideTheWindow() {
        assertThat(pending().decide(provider, OfferStatus.DECLINED, T0.plusSeconds(5)).status())
                .isEqualTo(OfferStatus.DECLINED);
    }

    @Test
    void decisionAtOrAfterTheDeadline_expiresTheOfferInstead() {
        JobOffer atDeadline = pending().decide(provider, OfferStatus.ACCEPTED, T0.plus(WINDOW));
        JobOffer afterDeadline = pending().decide(provider, OfferStatus.ACCEPTED, T0.plusSeconds(90));

        assertThat(atDeadline.status()).isEqualTo(OfferStatus.EXPIRED);
        assertThat(atDeadline.decidedAt()).isEqualTo(T0.plus(WINDOW));
        assertThat(afterDeadline.status()).isEqualTo(OfferStatus.EXPIRED);
    }

    @Test
    void anotherProvidersDecision_leavesTheOfferUntouched() {
        JobOffer offer = pending();

        assertThat(offer.decide(stranger, OfferStatus.ACCEPTED, T0)).isSameAs(offer);
    }

    @Test
    void aSecondDecision_leavesTheFirstStanding() {
        JobOffer declined = pending().decide(provider, OfferStatus.DECLINED, T0);

        assertThat(declined.decide(provider, OfferStatus.ACCEPTED, T0.plusSeconds(1))).isSameAs(declined);
    }

    @Test
    void decideRejectsNonDecisionStatuses() {
        assertThatThrownBy(() -> pending().decide(provider, OfferStatus.EXPIRED, T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pending().decide(provider, OfferStatus.PENDING, T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void closeExpiresOrWithdrawsAPendingOffer() {
        Instant at = T0.plus(WINDOW);

        assertThat(pending().close(provider, OfferStatus.EXPIRED, at).status()).isEqualTo(OfferStatus.EXPIRED);
        assertThat(pending().close(null, OfferStatus.WITHDRAWN, at).status()).isEqualTo(OfferStatus.WITHDRAWN);
        assertThat(pending().close(null, OfferStatus.WITHDRAWN, at).decidedAt()).isEqualTo(at);
    }

    @Test
    void closeKeepsADecisionThatLandedFirst() {
        JobOffer accepted = pending().decide(provider, OfferStatus.ACCEPTED, T0.plusSeconds(59));

        assertThat(accepted.close(provider, OfferStatus.EXPIRED, T0.plus(WINDOW))).isSameAs(accepted);
        assertThat(accepted.close(null, OfferStatus.WITHDRAWN, T0.plus(WINDOW))).isSameAs(accepted);
    }

    @Test
    void closeForAnotherProvider_leavesTheOfferUntouched() {
        // The booking was re-offered to someone else; the earlier offer's timeout must not close it.
        JobOffer offer = pending();

        assertThat(offer.close(stranger, OfferStatus.EXPIRED, T0.plus(WINDOW))).isSameAs(offer);
    }

    @Test
    void closeRejectsNonClosingStatuses() {
        assertThatThrownBy(() -> pending().close(provider, OfferStatus.ACCEPTED, T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyPendingIsNonTerminal() {
        assertThat(OfferStatus.PENDING.isTerminal()).isFalse();
        assertThat(List.of(OfferStatus.ACCEPTED, OfferStatus.DECLINED, OfferStatus.EXPIRED,
                OfferStatus.WITHDRAWN)).allMatch(OfferStatus::isTerminal);
    }
}
