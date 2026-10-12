package com.homefix.dispatch.service;

import com.homefix.dispatch.service.fake.InMemoryAcceptanceLedger;
import com.homefix.dispatch.service.fake.RecordingBookingTransition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reconciler finishes acceptances the dispatch thread could not, so a booking the Booking
 * Service accepted always gets its {@code ProviderAccepted} (Requirement 8.6).
 */
class AcceptanceReconcilerTest {

    private InMemoryAcceptanceLedger ledger;
    private RecordingBookingTransition bookingTransition;
    private AcceptanceReconciler reconciler;

    private final UUID booking = UUID.randomUUID();
    private final UUID customer = UUID.randomUUID();
    private final UUID provider = UUID.randomUUID();
    private final Instant createdAt = Instant.parse("2026-10-03T09:00:00Z");

    @BeforeEach
    void setUp() {
        ledger = new InMemoryAcceptanceLedger();
        bookingTransition = new RecordingBookingTransition();
        reconciler = new AcceptanceReconciler(ledger, bookingTransition);
        ledger.open(booking, customer, provider, createdAt);
    }

    @Test
    void retriesTheBookingTransitionThenAnnounces() {
        assertThat(reconciler.reconcile()).isEqualTo(1);

        assertThat(bookingTransition.acceptedProviderId()).isEqualTo(provider);
        assertThat(ledger.announcements()).containsExactly(
                new InMemoryAcceptanceLedger.Announcement(booking, customer, provider, createdAt));
        assertThat(ledger.outstanding()).isEmpty();
    }

    @Test
    void anAcceptanceTheBookingServiceAlreadyAppliedIsAnnouncedWithoutAskingAgain() {
        // By now the booking may be on its way; asking again would be refused and lose the event.
        ledger.markBookingAccepted(booking);
        bookingTransition.refuseAsNotSearchable();

        assertThat(reconciler.reconcile()).isEqualTo(1);

        assertThat(bookingTransition.acceptCalls()).isZero();
        assertThat(ledger.announcements()).hasSize(1);
    }

    @Test
    void bookingServiceStillUnavailableKeepsTheAcceptanceForALaterPass() {
        bookingTransition.unavailableFor(1);

        assertThat(reconciler.reconcile()).isZero();

        assertThat(ledger.outstanding()).containsKey(booking);
        assertThat(ledger.outstanding().get(booking).attempts()).isEqualTo(1);
        assertThat(ledger.published()).isFalse();

        assertThat(reconciler.reconcile()).isEqualTo(1);
        assertThat(ledger.announcements()).hasSize(1);
    }

    @Test
    void outboxFailureAfterTheTransitionRemembersThatTheBookingWasAccepted() {
        ledger.failAnnouncing();

        assertThat(reconciler.reconcile()).isZero();

        assertThat(ledger.outstanding().get(booking).bookingAccepted()).isTrue();
        assertThat(ledger.postponed()).containsExactly(booking);

        ledger.recover();
        bookingTransition.refuseAsNotSearchable(true);
        assertThat(reconciler.reconcile()).isEqualTo(1);
        assertThat(bookingTransition.acceptCalls()).isEqualTo(1);
    }

    @Test
    void refusedAcceptanceIsDroppedWithoutAnEvent() {
        // The customer cancelled while the Booking Service was unreachable: 409.
        bookingTransition.refuseAsNotSearchable();

        assertThat(reconciler.reconcile()).isZero();

        assertThat(ledger.discarded()).containsExactly(booking);
        assertThat(ledger.outstanding()).isEmpty();
        assertThat(ledger.published()).isFalse();
    }

    @Test
    void aFailingEntryDoesNotStopTheBatch() {
        UUID other = UUID.randomUUID();
        ledger.open(other, customer, UUID.randomUUID(), createdAt);
        bookingTransition.unavailableFor(1); // only the first entry's call fails

        assertThat(reconciler.reconcile()).isEqualTo(1);

        assertThat(ledger.outstanding()).containsOnlyKeys(booking);
        assertThat(ledger.announcements()).singleElement()
                .satisfies(a -> assertThat(a.bookingId()).isEqualTo(other));
    }
}
