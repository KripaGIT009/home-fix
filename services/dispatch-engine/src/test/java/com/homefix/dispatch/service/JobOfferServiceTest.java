package com.homefix.dispatch.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.homefix.dispatch.domain.DispatchRequest;
import com.homefix.dispatch.domain.JobOffer;
import com.homefix.dispatch.domain.OfferRound;
import com.homefix.dispatch.domain.OfferStatus;
import com.homefix.dispatch.service.JobOfferService.Decision;
import com.homefix.dispatch.service.JobOfferService.Decision.Outcome;
import com.homefix.dispatch.service.fake.InMemoryJobOfferStore;
import com.homefix.dispatch.service.fake.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link JobOfferService} over the in-memory store (Requirements 8.5-8.7). A booking is offered to a
 * wave of providers at once; decisions and the wave's timeout race on one compare-and-set, so
 * whichever lands first wins. The first accept takes the booking and withdraws everyone else's
 * offer, and a second provider's accept is refused as TAKEN. A late accept, someone else's decision
 * and a second decision are all refused; cancellation withdraws pending offers and nothing else.
 */
class JobOfferServiceTest {

    private static final Duration WINDOW = Duration.ofSeconds(60);

    private MutableClock clock;
    private InMemoryJobOfferStore store;
    private JobOfferService service;

    private final UUID bookingId = UUID.randomUUID();
    private final UUID provider = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();
    private final UUID third = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-02T10:00:00Z"));
        store = new InMemoryJobOfferStore();
        service = new JobOfferService(store, clock);
    }

    private DispatchRequest request(UUID booking) {
        return new DispatchRequest(booking, UUID.randomUUID(), 12.9, 77.6, UUID.randomUUID(),
                List.of("plumbing"), true, clock.instant(), "HFX-2026-0000042", null);
    }

    private List<JobOffer> open(UUID booking, Duration window, UUID... providers) {
        return service.open(request(booking), List.of(providers), window);
    }

    // ---- opening -------------------------------------------------------------------------------

    @Test
    void open_storesOnePendingOfferPerProviderThatOutlivesTheWindowByTheGrace() {
        List<JobOffer> wave = open(bookingId, WINDOW, provider, second, third);

        assertThat(wave).extracting(JobOffer::providerId).containsExactly(provider, second, third);
        assertThat(wave).allSatisfy(o -> {
            assertThat(o.status()).isEqualTo(OfferStatus.PENDING);
            assertThat(o.expiresAt()).isEqualTo(clock.instant().plus(WINDOW));
            assertThat(o.emergency()).isTrue();
            assertThat(o.reference()).isEqualTo("HFX-2026-0000042");
        });
        assertThat(store.find(bookingId)).get().extracting(OfferRound::offers).asList().hasSize(3);
        assertThat(store.ttlOf(bookingId)).isEqualTo(WINDOW.plus(JobOfferService.RETENTION_GRACE));
    }

    @Test
    void open_addsALaterWaveAlongsideTheEarlierOffers() {
        open(bookingId, WINDOW, provider);
        service.decide(bookingId, provider, OfferStatus.DECLINED);

        open(bookingId, WINDOW, second, third);

        assertThat(service.statusOf(bookingId, provider)).contains(OfferStatus.DECLINED);
        assertThat(service.statusOf(bookingId, second)).contains(OfferStatus.PENDING);
        assertThat(service.statusOf(bookingId, third)).contains(OfferStatus.PENDING);
    }

    @Test
    void open_onceSomeoneAccepted_opensNothing() {
        open(bookingId, WINDOW, provider);
        service.decide(bookingId, provider, OfferStatus.ACCEPTED);

        assertThat(open(bookingId, WINDOW, second)).isEmpty();
        assertThat(service.statusOf(bookingId, second)).isEmpty();
    }

    // ---- provider decisions --------------------------------------------------------------------

    @Test
    void firstAccept_takesTheBookingAndWithdrawsTheRestOfTheWave() {
        open(bookingId, WINDOW, provider, second, third);
        clock.advance(Duration.ofSeconds(20));

        Decision decision = service.decide(bookingId, second, OfferStatus.ACCEPTED);

        assertThat(decision.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(decision.offer().status()).isEqualTo(OfferStatus.ACCEPTED);
        assertThat(service.statusOf(bookingId, provider)).contains(OfferStatus.WITHDRAWN);
        assertThat(service.statusOf(bookingId, third)).contains(OfferStatus.WITHDRAWN);
        assertThat(service.pendingFor(provider)).isEmpty();
        assertThat(service.pendingFor(third)).isEmpty();
    }

    @Test
    void secondAccept_isRefusedAsTakenAndTheFirstStands() {
        open(bookingId, WINDOW, provider, second);
        service.decide(bookingId, provider, OfferStatus.ACCEPTED);

        Decision late = service.decide(bookingId, second, OfferStatus.ACCEPTED);

        assertThat(late.outcome()).isEqualTo(Outcome.TAKEN);
        assertThat(late.offer().status()).isEqualTo(OfferStatus.WITHDRAWN);
        assertThat(service.round(bookingId)).get()
                .extracting(r -> r.accepted().orElseThrow().providerId()).isEqualTo(provider);
    }

    @Test
    void aDecline_leavesTheOthersInTheWaveOpen() {
        open(bookingId, WINDOW, provider, second);

        Decision decision = service.decide(bookingId, provider, OfferStatus.DECLINED);

        assertThat(decision.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(service.statusOf(bookingId, provider)).contains(OfferStatus.DECLINED);
        assertThat(service.statusOf(bookingId, second)).contains(OfferStatus.PENDING);
        assertThat(service.decide(bookingId, second, OfferStatus.ACCEPTED).outcome()).isEqualTo(Outcome.APPLIED);
    }

    @Test
    void lateAccept_beforeTheTimeoutRan_isRefusedAndExpiresTheOffer() {
        open(bookingId, WINDOW, provider);
        clock.advance(WINDOW);

        Decision decision = service.decide(bookingId, provider, OfferStatus.ACCEPTED);

        assertThat(decision.outcome()).isEqualTo(Outcome.EXPIRED);
        assertThat(decision.offer().status()).isEqualTo(OfferStatus.EXPIRED);
        // The dispatch thread's close then sees EXPIRED, not an accept.
        assertThat(service.expire(bookingId, List.of(provider)).offerTo(provider))
                .get().extracting(JobOffer::status).isEqualTo(OfferStatus.EXPIRED);
    }

    @Test
    void lateAccept_afterTheTimeoutRan_isRefused() {
        open(bookingId, WINDOW, provider);
        clock.advance(WINDOW);
        service.expire(bookingId, List.of(provider));

        Decision decision = service.decide(bookingId, provider, OfferStatus.ACCEPTED);

        assertThat(decision.outcome()).isEqualTo(Outcome.EXPIRED);
        assertThat(service.statusOf(bookingId, provider)).contains(OfferStatus.EXPIRED);
    }

    @Test
    void aStrangersDecision_isNotFoundAndLeavesTheOffersPending() {
        open(bookingId, WINDOW, provider);

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
    void secondDecisionBySameProvider_isRefusedAndTheFirstStands() {
        open(bookingId, WINDOW, provider);
        service.decide(bookingId, provider, OfferStatus.DECLINED);

        Decision again = service.decide(bookingId, provider, OfferStatus.ACCEPTED);

        assertThat(again.outcome()).isEqualTo(Outcome.ALREADY_DECIDED);
        assertThat(again.offer().status()).isEqualTo(OfferStatus.DECLINED);
        assertThat(service.statusOf(bookingId, provider)).contains(OfferStatus.DECLINED);
    }

    @Test
    void acceptOfAnOfferWithdrawnByCancellation_isRefusedAsAlreadyDecided() {
        open(bookingId, WINDOW, provider);
        service.withdraw(bookingId);

        Decision decision = service.decide(bookingId, provider, OfferStatus.ACCEPTED);

        assertThat(decision.outcome()).isEqualTo(Outcome.ALREADY_DECIDED);
        assertThat(decision.offer().status()).isEqualTo(OfferStatus.WITHDRAWN);
    }

    // ---- timeout -------------------------------------------------------------------------------

    @Test
    void expire_keepsADecisionThatLandedFirstAndClosesTheRest() {
        open(bookingId, WINDOW, provider, second, third);
        service.decide(bookingId, provider, OfferStatus.DECLINED);
        clock.advance(WINDOW);

        OfferRound after = service.expire(bookingId, List.of(provider, second, third));

        assertThat(after.offerTo(provider)).get().extracting(JobOffer::status).isEqualTo(OfferStatus.DECLINED);
        assertThat(after.offerTo(second)).get().extracting(JobOffer::status).isEqualTo(OfferStatus.EXPIRED);
        assertThat(after.offerTo(third)).get().extracting(JobOffer::status).isEqualTo(OfferStatus.EXPIRED);
    }

    @Test
    void expire_onlyClosesTheGivenProviders() {
        open(bookingId, WINDOW, provider);
        open(bookingId, WINDOW, second);

        service.expire(bookingId, List.of(provider));

        assertThat(service.statusOf(bookingId, provider)).contains(OfferStatus.EXPIRED);
        assertThat(service.statusOf(bookingId, second)).contains(OfferStatus.PENDING);
    }

    @Test
    void expire_ofOffersGoneFromStorage_returnsAnEmptyRound() {
        open(bookingId, WINDOW, provider);
        store.evict(bookingId);

        assertThat(service.expire(bookingId, List.of(provider)).isEmpty()).isTrue();
    }

    // ---- withdraw ------------------------------------------------------------------------------

    @Test
    void withdraw_closesEveryPendingOffer() {
        open(bookingId, WINDOW, provider, second);

        service.withdraw(bookingId);

        assertThat(service.statusOf(bookingId, provider)).contains(OfferStatus.WITHDRAWN);
        assertThat(service.statusOf(bookingId, second)).contains(OfferStatus.WITHDRAWN);
        assertThat(service.pendingFor(provider)).isEmpty();
    }

    @Test
    void withdraw_keepsAnAcceptedOfferAndToleratesNoOffer() {
        open(bookingId, WINDOW, provider);
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
        open(later, Duration.ofSeconds(60), provider);
        open(sooner, Duration.ofSeconds(30), provider);
        open(decided, Duration.ofSeconds(45), provider);
        service.decide(decided, provider, OfferStatus.DECLINED);
        open(someoneElses, Duration.ofSeconds(10), stranger);

        assertThat(service.pendingFor(provider)).extracting(JobOffer::bookingId)
                .containsExactly(sooner, later);

        clock.advance(Duration.ofSeconds(30));
        assertThat(service.pendingFor(provider)).extracting(JobOffer::bookingId)
                .containsExactly(later);
    }

    @Test
    void pendingFor_showsTheSameBookingToEveryProviderInTheWave() {
        open(bookingId, WINDOW, provider, second);

        assertThat(service.pendingFor(provider)).extracting(JobOffer::bookingId).containsExactly(bookingId);
        assertThat(service.pendingFor(second)).extracting(JobOffer::bookingId).containsExactly(bookingId);
        assertThat(service.pendingFor(stranger)).isEmpty();
    }

    @Test
    void viewFor_showsAnOfferOnlyToItsProviderInAnyStatus() {
        open(bookingId, WINDOW, provider);
        service.decide(bookingId, provider, OfferStatus.DECLINED);

        assertThat(service.viewFor(bookingId, provider)).get()
                .extracting(JobOffer::status).isEqualTo(OfferStatus.DECLINED);
        assertThat(service.viewFor(bookingId, stranger)).isEmpty();
        assertThat(service.statusOf(bookingId, stranger)).isEmpty();
    }
}
