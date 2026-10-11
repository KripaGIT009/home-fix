package com.homefix.dispatch.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * The {@link OfferRound} transitions (Requirements 8.5-8.7): a booking offered to several providers
 * at once can be accepted by exactly one of them, and that accept withdraws everyone else's offer in
 * the same step.
 */
class OfferRoundTest {

    private static final Instant T0 = Instant.parse("2026-10-02T10:00:00Z");
    private static final Duration WINDOW = Duration.ofSeconds(60);

    private final UUID bookingId = UUID.randomUUID();
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();

    private JobOffer pending(UUID provider) {
        return JobOffer.pending(bookingId, provider, T0, WINDOW);
    }

    private OfferRound wave(UUID... providers) {
        return OfferRound.empty(bookingId).open(List.of(providers).stream().map(this::pending).toList());
    }

    private static OfferStatus statusOf(OfferRound round, UUID provider) {
        return round.offerTo(provider).orElseThrow().status();
    }

    @Test
    void open_addsAPendingOfferPerProviderInOrder() {
        OfferRound round = wave(a, b, c);

        assertThat(round.offers()).extracting(JobOffer::providerId).containsExactly(a, b, c);
        assertThat(round.offers()).allMatch(o -> o.status() == OfferStatus.PENDING);
        assertThat(round.accepted()).isEmpty();
    }

    @Test
    void open_replacesAnEarlierOfferToTheSameProvider() {
        OfferRound declined = wave(a).decide(a, OfferStatus.DECLINED, T0.plusSeconds(5));

        OfferRound reopened = declined.open(List.of(JobOffer.pending(bookingId, a, T0.plusSeconds(90), WINDOW)));

        assertThat(reopened.offers()).hasSize(1);
        assertThat(statusOf(reopened, a)).isEqualTo(OfferStatus.PENDING);
    }

    @Test
    void open_onceAccepted_changesNothing() {
        OfferRound taken = wave(a).decide(a, OfferStatus.ACCEPTED, T0.plusSeconds(5));

        assertThat(taken.open(List.of(pending(b)))).isSameAs(taken);
    }

    @Test
    void accept_takesTheBookingAndWithdrawsEveryOtherPendingOffer() {
        OfferRound round = wave(a, b, c).decide(a, OfferStatus.DECLINED, T0.plusSeconds(3));

        OfferRound taken = round.decide(b, OfferStatus.ACCEPTED, T0.plusSeconds(5));

        assertThat(taken.accepted()).get().extracting(JobOffer::providerId).isEqualTo(b);
        assertThat(statusOf(taken, a)).isEqualTo(OfferStatus.DECLINED);
        assertThat(statusOf(taken, c)).isEqualTo(OfferStatus.WITHDRAWN);
        assertThat(taken.offerTo(c).orElseThrow().decidedAt()).isEqualTo(T0.plusSeconds(5));
    }

    @Test
    void aSecondAccept_changesNothing() {
        OfferRound taken = wave(a, b).decide(a, OfferStatus.ACCEPTED, T0.plusSeconds(5));

        assertThat(taken.decide(b, OfferStatus.ACCEPTED, T0.plusSeconds(6))).isEqualTo(taken);
    }

    @Test
    void aLateAccept_expiresOnlyThatOffer() {
        OfferRound round = wave(a, b).decide(a, OfferStatus.ACCEPTED, T0.plus(WINDOW));

        assertThat(statusOf(round, a)).isEqualTo(OfferStatus.EXPIRED);
        assertThat(statusOf(round, b)).isEqualTo(OfferStatus.PENDING);
        assertThat(round.accepted()).isEmpty();
    }

    @Test
    void aDecisionByAStranger_changesNothing() {
        OfferRound round = wave(a, b);

        assertThat(round.decide(UUID.randomUUID(), OfferStatus.ACCEPTED, T0)).isSameAs(round);
    }

    @Test
    void close_closesOnlyTheNamedPendingOffers() {
        OfferRound round = wave(a, b, c).decide(a, OfferStatus.DECLINED, T0.plusSeconds(1));

        OfferRound closed = round.close(List.of(a, b), OfferStatus.EXPIRED, T0.plus(WINDOW));

        assertThat(statusOf(closed, a)).isEqualTo(OfferStatus.DECLINED);
        assertThat(statusOf(closed, b)).isEqualTo(OfferStatus.EXPIRED);
        assertThat(statusOf(closed, c)).isEqualTo(OfferStatus.PENDING);
    }

    @Test
    void close_withNoProviders_withdrawsEveryPendingOfferButKeepsAnAccept() {
        OfferRound taken = wave(a, b).decide(a, OfferStatus.ACCEPTED, T0.plusSeconds(5));
        OfferRound open = wave(a, b);

        assertThat(taken.close(null, OfferStatus.WITHDRAWN, T0.plusSeconds(9))).isEqualTo(taken);
        assertThat(open.close(null, OfferStatus.WITHDRAWN, T0.plusSeconds(9)).offers())
                .allMatch(o -> o.status() == OfferStatus.WITHDRAWN);
    }

    @Test
    void anOfferForAnotherBooking_isRefused() {
        JobOffer elsewhere = JobOffer.pending(UUID.randomUUID(), a, T0, WINDOW);

        assertThatThrownBy(() -> new OfferRound(bookingId, List.of(elsewhere)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
