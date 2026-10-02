package com.homefix.dispatch.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.homefix.dispatch.domain.DispatchRequest;
import com.homefix.dispatch.domain.JobOffer;
import com.homefix.dispatch.domain.OfferStatus;
import com.homefix.dispatch.service.JobOfferService.Decision;
import com.homefix.dispatch.service.JobOfferService.Decision.Outcome;
import com.homefix.dispatch.service.fake.InMemoryJobOfferStore;
import com.homefix.dispatch.service.fake.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link JobOfferService} over the in-memory store (Requirements 8.5-8.7): the provider's decision
 * and the dispatch timeout race on one compare-and-set, so whichever lands first wins; a late
 * accept, someone else's decision and a second decision are all refused; cancellation withdraws a
 * pending offer and nothing else.
 */
class JobOfferServiceTest {

    private static final Duration WINDOW = Duration.ofSeconds(60);

    private MutableClock clock;
    private InMemoryJobOfferStore store;
    private JobOfferService service;

    private final UUID bookingId = UUID.randomUUID();
    private final UUID provider = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-02T10:00:00Z"));
        store = new InMemoryJobOfferStore();
        service = new JobOfferService(store, clock);
    }

    // ---- opening -------------------------------------------------------------------------------

    @Test
    void open_storesAPendingOfferThatOutlivesItsWindowByTheGrace() {
        JobOffer offer = service.open(bookingId, provider, WINDOW);

        assertThat(offer.status()).isEqualTo(OfferStatus.PENDING);
        assertThat(offer.expiresAt()).isEqualTo(clock.instant().plus(WINDOW));
        assertThat(store.find(bookingId)).contains(offer);
        assertThat(store.ttlOf(bookingId)).isEqualTo(WINDOW.plus(JobOfferService.RETENTION_GRACE));
    }

    @Test
    void openFromRequest_keepsTheDisplayFields() {
        DispatchRequest request = new DispatchRequest(bookingId, UUID.randomUUID(), 12.9, 77.6,
                UUID.randomUUID(), List.of("plumbing"), true, clock.instant(), "HFX-2026-0000042", null);

        JobOffer offer = service.open(request, provider, WINDOW);

        assertThat(offer.emergency()).isTrue();
        assertThat(offer.reference()).isEqualTo("HFX-2026-0000042");
        assertThat(store.find(bookingId)).contains(offer);
    }

    // ---- provider decisions --------------------------------------------------------------------

    @Test
    void pendingOffer_isAcceptedByItsProvider() {
        service.open(bookingId, provider, WINDOW);
        clock.advance(Duration.ofSeconds(20));

        Decision decision = service.decide(bookingId, provider, OfferStatus.ACCEPTED);

        assertThat(decision.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(decision.offer().status()).isEqualTo(OfferStatus.ACCEPTED);
        assertThat(service.statusOf(bookingId, provider)).contains(OfferStatus.ACCEPTED);
    }

    @Test
    void pendingOffer_isDeclinedByItsProvider() {
        service.open(bookingId, provider, WINDOW);

        Decision decision = service.decide(bookingId, provider, OfferStatus.DECLINED);

        assertThat(decision.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(service.statusOf(bookingId, provider)).contains(OfferStatus.DECLINED);
    }

    @Test
    void lateAccept_beforeTheTimeoutRan_isRefusedAndExpiresTheOffer() {
        service.open(bookingId, provider, WINDOW);
        clock.advance(WINDOW);

        Decision decision = service.decide(bookingId, provider, OfferStatus.ACCEPTED);

        assertThat(decision.outcome()).isEqualTo(Outcome.EXPIRED);
        assertThat(decision.offer().status()).isEqualTo(OfferStatus.EXPIRED);
        // The dispatch thread's close then sees EXPIRED, not an accept.
        assertThat(service.expire(bookingId, provider)).isEqualTo(OfferStatus.EXPIRED);
    }

    @Test
    void lateAccept_afterTheTimeoutRan_isRefused() {
        service.open(bookingId, provider, WINDOW);
        clock.advance(WINDOW);
        assertThat(service.expire(bookingId, provider)).isEqualTo(OfferStatus.EXPIRED);

        Decision decision = service.decide(bookingId, provider, OfferStatus.ACCEPTED);

        assertThat(decision.outcome()).isEqualTo(Outcome.EXPIRED);
        assertThat(service.statusOf(bookingId, provider)).contains(OfferStatus.EXPIRED);
    }

    @Test
    void anotherProvidersDecision_isNotFoundAndLeavesTheOfferPending() {
        service.open(bookingId, provider, WINDOW);

        Decision decision = service.decide(bookingId, stranger, OfferStatus.ACCEPTED);

        assertThat(decision.outcome()).isEqualTo(Outcome.NOT_FOUND);
        assertThat(decision.offer()).isNull();
        assertThat(service.statusOf(bookingId, provider)).contains(OfferStatus.PENDING);
    }

    @Test
    void decisionOnABookingWithNoOffer_isNotFound() {
        assertThat(service.decide(bookingId, provider, OfferStatus.ACCEPTED).outcome())
                .isEqualTo(Outcome.NOT_FOUND);
    }

    @Test
    void secondDecision_isRefusedAndTheFirstStands() {
        service.open(bookingId, provider, WINDOW);
        service.decide(bookingId, provider, OfferStatus.DECLINED);

        Decision second = service.decide(bookingId, provider, OfferStatus.ACCEPTED);

        assertThat(second.outcome()).isEqualTo(Outcome.ALREADY_DECIDED);
        assertThat(second.offer().status()).isEqualTo(OfferStatus.DECLINED);
        assertThat(service.statusOf(bookingId, provider)).contains(OfferStatus.DECLINED);
    }

    @Test
    void acceptOfAWithdrawnOffer_isRefusedAsAlreadyDecided() {
        service.open(bookingId, provider, WINDOW);
        service.withdraw(bookingId);

        Decision decision = service.decide(bookingId, provider, OfferStatus.ACCEPTED);

        assertThat(decision.outcome()).isEqualTo(Outcome.ALREADY_DECIDED);
        assertThat(decision.offer().status()).isEqualTo(OfferStatus.WITHDRAWN);
    }

    // ---- timeout -------------------------------------------------------------------------------

    @Test
    void expire_keepsADecisionThatLandedFirst() {
        service.open(bookingId, provider, WINDOW);
        service.decide(bookingId, provider, OfferStatus.ACCEPTED);
        clock.advance(WINDOW);

        assertThat(service.expire(bookingId, provider)).isEqualTo(OfferStatus.ACCEPTED);
    }

    @Test
    void expire_closesAnUnansweredOffer() {
        service.open(bookingId, provider, WINDOW);
        clock.advance(WINDOW);

        assertThat(service.expire(bookingId, provider)).isEqualTo(OfferStatus.EXPIRED);
        assertThat(service.statusOf(bookingId, provider)).contains(OfferStatus.EXPIRED);
    }

    @Test
    void expire_ofAnOfferGoneFromStorage_reportsExpired() {
        service.open(bookingId, provider, WINDOW);
        store.evict(bookingId);

        assertThat(service.expire(bookingId, provider)).isEqualTo(OfferStatus.EXPIRED);
    }

    @Test
    void expire_ofAnOfferSinceMadeToSomeoneElse_reportsExpiredAndLeavesTheirsOpen() {
        service.open(bookingId, provider, WINDOW);
        service.open(bookingId, stranger, WINDOW);

        assertThat(service.expire(bookingId, provider)).isEqualTo(OfferStatus.EXPIRED);
        assertThat(service.statusOf(bookingId, stranger)).contains(OfferStatus.PENDING);
    }

    // ---- withdraw ------------------------------------------------------------------------------

    @Test
    void withdraw_closesAPendingOffer() {
        service.open(bookingId, provider, WINDOW);

        service.withdraw(bookingId);

        assertThat(service.statusOf(bookingId, provider)).contains(OfferStatus.WITHDRAWN);
        assertThat(service.pendingFor(provider)).isEmpty();
    }

    @Test
    void withdraw_keepsADecidedOfferAndToleratesNoOffer() {
        service.open(bookingId, provider, WINDOW);
        service.decide(bookingId, provider, OfferStatus.ACCEPTED);

        service.withdraw(bookingId);
        service.withdraw(UUID.randomUUID());

        assertThat(service.statusOf(bookingId, provider)).contains(OfferStatus.ACCEPTED);
    }

    // ---- reads ---------------------------------------------------------------------------------

    @Test
    void pendingFor_listsOnlyTheCallersOpenOffersSoonestExpiringFirst() {
        UUID later = UUID.randomUUID();
        UUID sooner = UUID.randomUUID();
        UUID decided = UUID.randomUUID();
        UUID someoneElses = UUID.randomUUID();
        service.open(later, provider, Duration.ofSeconds(60));
        service.open(sooner, provider, Duration.ofSeconds(30));
        service.open(decided, provider, Duration.ofSeconds(45));
        service.decide(decided, provider, OfferStatus.DECLINED);
        service.open(someoneElses, stranger, Duration.ofSeconds(10));

        assertThat(service.pendingFor(provider)).extracting(JobOffer::bookingId)
                .containsExactly(sooner, later);

        clock.advance(Duration.ofSeconds(30));
        assertThat(service.pendingFor(provider)).extracting(JobOffer::bookingId)
                .containsExactly(later);
    }

    @Test
    void pendingFor_skipsAnOfferThatWasReofferedToSomeoneElse() {
        service.open(bookingId, provider, WINDOW);
        service.open(bookingId, stranger, WINDOW);

        assertThat(service.pendingFor(provider)).isEmpty();
        assertThat(service.pendingFor(stranger)).hasSize(1);
    }

    @Test
    void viewFor_showsAnOfferOnlyToItsProviderInAnyStatus() {
        service.open(bookingId, provider, WINDOW);
        service.decide(bookingId, provider, OfferStatus.DECLINED);

        assertThat(service.viewFor(bookingId, provider)).get()
                .extracting(JobOffer::status).isEqualTo(OfferStatus.DECLINED);
        assertThat(service.viewFor(bookingId, stranger)).isEmpty();
        assertThat(service.statusOf(bookingId, stranger)).isEmpty();
    }
}
