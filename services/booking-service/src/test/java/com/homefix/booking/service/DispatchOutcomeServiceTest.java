package com.homefix.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;

import com.homefix.booking.address.CustomerAddressPort.ServiceAddress;
import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingAudit;
import com.homefix.booking.domain.BookingAuditRepository;
import com.homefix.booking.domain.BookingRepository;
import com.homefix.booking.domain.BookingStateMachine;
import com.homefix.booking.domain.BookingStatus;
import com.homefix.booking.domain.BookingTenantCandidate;
import com.homefix.booking.event.BookingCancelledEvent;
import com.homefix.booking.support.FakeTenantDirectory;
import com.homefix.booking.support.InMemoryBookingTenantCandidateRepository;
import com.homefix.booking.tenant.TenantDirectoryPort.CoveringTenant;
import com.homefix.shared.outbox.OutboxEventPublisher;

/**
 * Tests for the transitions the Dispatch Engine requests (Requirements 8.6, 8.9) and the Tenant
 * fallback behind its searching-failed callback (Requirement MT-4, MT-8.1, MT-8.2).
 *
 * <p>These cover the defect that left the core flow broken: the Dispatch Engine asked for
 * {@code SEARCHING_PROVIDER -> PROVIDER_ACCEPTED} directly, which the state machine forbids, against
 * an endpoint that did not exist. The service now walks the legal two-step path and assigns the
 * provider, and both entry points tolerate the retries the caller's resilience stack performs.
 *
 * <p>The state machine, transition service, audit trail and lifecycle-event publisher are real; the
 * booking repository and the outbox writer are stubbed, the candidates and the Tenant directory are
 * in-memory fakes, and the transaction is a pass-through.
 */
class DispatchOutcomeServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-11T09:30:00Z"), ZoneOffset.UTC);

    private BookingRepository bookingRepository;
    private BookingAuditRepository auditRepository;
    private OutboxEventPublisher outboxPublisher;
    private InMemoryBookingTenantCandidateRepository candidates;
    private FakeTenantDirectory tenants;
    /** The booking's service address; empty (unresolved) unless a test sets it. */
    private Optional<ServiceAddress> address;
    private DispatchOutcomeService service;

    @BeforeEach
    void setUp() {
        bookingRepository = mock(BookingRepository.class);
        auditRepository = mock(BookingAuditRepository.class);
        when(auditRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        outboxPublisher = mock(OutboxEventPublisher.class);
        candidates = new InMemoryBookingTenantCandidateRepository();
        tenants = new FakeTenantDirectory();
        address = Optional.empty();
        service = new DispatchOutcomeService(bookingRepository,
                new BookingTransitionService(new BookingStateMachine(), auditRepository,
                        new BookingLifecycleEventPublisher(outboxPublisher, CLOCK), CLOCK),
                candidates, id -> address, tenants, TransactionOperations.withoutTransaction(), CLOCK);
    }

    private Booking searchingBooking() {
        Booking booking = Booking.create("HFX-2026-0004821", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), true, null, new BigDecimal("1250.00"));
        booking.applyStatus(BookingStatus.SEARCHING_PROVIDER);
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        return booking;
    }

    private void coveredBy(UUID... tenantIds) {
        address = Optional.of(new ServiceAddress("12 Station Rd, Ara", 25.556, 84.663));
        for (UUID id : tenantIds) {
            tenants.covering.add(new CoveringTenant(id, "Partner " + id, 1.5));
        }
    }

    // ----- Acceptance (Requirement 8.6) -----

    @Test
    void acceptance_walksThroughProviderAssignedAndAssignsTheProvider() {
        Booking booking = searchingBooking();
        UUID providerId = UUID.randomUUID();

        Booking result = service.markProviderAccepted(booking.getId(), providerId);

        assertThat(result.getStatus()).isEqualTo(BookingStatus.PROVIDER_ACCEPTED);
        // The provider is recorded on the aggregate; before this, providerId was never set and every
        // downstream provider event carried a null provider.
        assertThat(result.getProviderId()).isEqualTo(providerId);
    }

    @Test
    void acceptance_isIdempotentForTheSameProvider() {
        Booking booking = searchingBooking();
        UUID providerId = UUID.randomUUID();

        service.markProviderAccepted(booking.getId(), providerId);
        Booking second = service.markProviderAccepted(booking.getId(), providerId);

        assertThat(second.getStatus()).isEqualTo(BookingStatus.PROVIDER_ACCEPTED);
        assertThat(second.getProviderId()).isEqualTo(providerId);
    }

    @Test
    void acceptance_rejectsADifferentProviderOnAnAlreadyAcceptedBooking() {
        Booking booking = searchingBooking();
        service.markProviderAccepted(booking.getId(), UUID.randomUUID());

        assertThatThrownBy(() -> service.markProviderAccepted(booking.getId(), UUID.randomUUID()))
                .isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void acceptance_rejectsAMissingProviderId() {
        Booking booking = searchingBooking();

        assertThatThrownBy(() -> service.markProviderAccepted(booking.getId(), null))
                .isInstanceOf(BookingException.class);
    }

    @Test
    void acceptance_rejectsATransitionThatIsNotLegalFromTheCurrentState() {
        Booking booking = Booking.create("HFX-2026-0004822", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), false, null, new BigDecimal("100.00"));
        // Still CREATED: the customer has not confirmed, so nothing may be dispatched yet.
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.markProviderAccepted(booking.getId(), UUID.randomUUID()))
                .isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void acceptance_rejectsAnUnknownBooking() {
        UUID unknown = UUID.randomUUID();
        when(bookingRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markProviderAccepted(unknown, UUID.randomUUID()))
                .isInstanceOf(BookingException.class);
    }

    // ----- Exhausted search (Requirement 8.9) -----

    @Test
    void searchingFailed_marksTheBookingFailed() {
        Booking booking = searchingBooking();

        Booking result = service.markSearchingFailed(booking.getId());

        assertThat(result.getStatus()).isEqualTo(BookingStatus.SEARCHING_FAILED);
    }

    @Test
    void searchingFailed_isIdempotent() {
        Booking booking = searchingBooking();

        service.markSearchingFailed(booking.getId());
        Booking second = service.markSearchingFailed(booking.getId());

        assertThat(second.getStatus()).isEqualTo(BookingStatus.SEARCHING_FAILED);
    }

    @Test
    void searchingFailed_rejectsAnUnknownBooking() {
        UUID unknown = UUID.randomUUID();
        when(bookingRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markSearchingFailed(unknown))
                .isInstanceOf(BookingException.class);
    }

    // ----- State-driven events (Requirement 22.1) -----

    @Test
    void acceptance_publishesNoProviderAssignedButStillAuditsTheIntermediateStep() {
        Booking booking = searchingBooking();
        UUID providerId = UUID.randomUUID();

        service.markProviderAccepted(booking.getId(), providerId);
        service.markProviderAccepted(booking.getId(), providerId); // redelivered callback

        // PROVIDER_ASSIGNED is passed through inside one transaction, so nobody can observe it:
        // announcing it would precede the Dispatch Engine's ProviderAccepted with a stale
        // "assigned" notice. booking-service writes no outbox row for the acceptance at all.
        verify(outboxPublisher, never()).publish(any(), any(), any(), any());

        // The audit chain still records both steps (Property 9).
        ArgumentCaptor<BookingAudit> audits = ArgumentCaptor.forClass(BookingAudit.class);
        verify(auditRepository, times(2)).save(audits.capture());
        assertThat(audits.getAllValues()).extracting(BookingAudit::getToState)
                .containsExactly(BookingStatus.PROVIDER_ASSIGNED, BookingStatus.PROVIDER_ACCEPTED);
    }

    @Test
    void searchingFailed_writesExactlyOneBookingCancelled() {
        Booking booking = searchingBooking();

        service.markSearchingFailed(booking.getId());
        // A redelivered callback is a no-op and must not publish again.
        service.markSearchingFailed(booking.getId());

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxPublisher, times(1)).publish(eq(BookingCancelledEvent.AGGREGATE_TYPE),
                eq(booking.getId()), eq(BookingCancelledEvent.EVENT_TYPE), payload.capture());
        BookingCancelledEvent event = (BookingCancelledEvent) payload.getValue();
        assertThat(event.status()).isEqualTo(BookingStatus.SEARCHING_FAILED);
        assertThat(event.previousStatus()).isEqualTo(BookingStatus.SEARCHING_PROVIDER);
        assertThat(event.customerId()).isEqualTo(booking.getCustomerId());
        verify(outboxPublisher, times(1)).publish(any(), any(), any(), any());
    }

    // ----- Tenant fallback (Requirement MT-4, Property MT1) -----

    @Test
    void searchingFailed_withCoveringTenants_queuesTheBookingWithoutBookingCancelled() {
        Booking booking = searchingBooking();
        UUID near = UUID.randomUUID();
        UUID far = UUID.randomUUID();
        coveredBy(near, far);

        Booking result = service.markSearchingFailed(booking.getId());

        assertThat(result.getStatus()).isEqualTo(BookingStatus.AWAITING_ASSIGNMENT);
        assertThat(result.getQueuedForAssignmentAt()).isEqualTo(CLOCK.instant());
        assertThat(result.getTenantId()).isNull();
        assertThat(candidates.findByBookingId(booking.getId()))
                .extracting(BookingTenantCandidate::getTenantId).containsExactlyInAnyOrder(near, far);
        // Requirement MT-4.2: the customer is not told the booking failed.
        verify(outboxPublisher, never()).publish(any(), any(), any(), any());
        ArgumentCaptor<BookingAudit> audit = ArgumentCaptor.forClass(BookingAudit.class);
        verify(auditRepository).save(audit.capture());
        assertThat(audit.getValue().getFromState()).isEqualTo(BookingStatus.SEARCHING_PROVIDER);
        assertThat(audit.getValue().getToState()).isEqualTo(BookingStatus.AWAITING_ASSIGNMENT);
        assertThat(audit.getValue().getReason()).contains("routed to 2 partner(s)");
    }

    @Test
    void searchingFailed_withNoCoveringTenant_failsAsBefore() {
        Booking booking = searchingBooking();
        coveredBy(); // the address resolves, nobody covers it

        Booking result = service.markSearchingFailed(booking.getId());

        assertThat(result.getStatus()).isEqualTo(BookingStatus.SEARCHING_FAILED);
        assertThat(result.getQueuedForAssignmentAt()).isNull();
        verify(outboxPublisher).publish(eq(BookingCancelledEvent.AGGREGATE_TYPE), eq(booking.getId()),
                eq(BookingCancelledEvent.EVENT_TYPE), any());
    }

    @Test
    void searchingFailed_whenTheCoverageLookupFails_failsTheBooking() {
        Booking booking = searchingBooking();
        coveredBy(UUID.randomUUID());
        tenants.down = true;

        Booking result = service.markSearchingFailed(booking.getId());

        // Requirement MT-4.4: an outage never leaves the booking in SEARCHING_PROVIDER.
        assertThat(result.getStatus()).isEqualTo(BookingStatus.SEARCHING_FAILED);
        assertThat(candidates.findByBookingId(booking.getId())).isEmpty();
    }

    @Test
    void searchingFailed_whenTheAddressCannotBeResolved_failsTheBooking() {
        Booking booking = searchingBooking();
        tenants.covering.add(new CoveringTenant(UUID.randomUUID(), "Partner", 1.0));
        address = Optional.empty();

        Booking result = service.markSearchingFailed(booking.getId());

        assertThat(result.getStatus()).isEqualTo(BookingStatus.SEARCHING_FAILED);
        assertThat(candidates.findByBookingId(booking.getId())).isEmpty();
    }

    @Test
    void searchingFailed_redeliveredAfterFallback_changesNothing() {
        Booking booking = searchingBooking();
        coveredBy(UUID.randomUUID());
        service.markSearchingFailed(booking.getId());
        // A Tenant assigned meanwhile; the late duplicate must neither fail nor re-queue it.
        booking.applyStatus(BookingStatus.PROVIDER_ASSIGNED);

        Booking second = service.markSearchingFailed(booking.getId());

        assertThat(second.getStatus()).isEqualTo(BookingStatus.PROVIDER_ASSIGNED);
        assertThat(candidates.findByBookingId(booking.getId())).hasSize(1);
        verify(auditRepository, times(1)).save(any());
        verify(outboxPublisher, never()).publish(any(), any(), any(), any());
    }

    @Test
    void searchingFailed_onACancelledBooking_isStillRefusedWithoutLookups() {
        Booking booking = searchingBooking();
        booking.applyStatus(BookingStatus.CANCELLED);
        coveredBy(UUID.randomUUID());
        tenants.down = true; // would throw if it were asked

        assertThatThrownBy(() -> service.markSearchingFailed(booking.getId()))
                .isInstanceOf(InvalidTransitionException.class);
    }

    // ----- Tenant on automatic acceptance (Requirement MT-8.1, MT-8.2) -----

    @Test
    void acceptance_byATenantProvider_recordsTheTenant() {
        Booking booking = searchingBooking();
        UUID providerId = UUID.randomUUID();
        UUID tenantId = tenants.addTenant("Ara Home Services", "ACTIVE").tenantId();
        tenants.addMember(tenantId, providerId, true);

        Booking result = service.markProviderAccepted(booking.getId(), providerId);

        assertThat(result.getStatus()).isEqualTo(BookingStatus.PROVIDER_ACCEPTED);
        assertThat(result.getTenantId()).isEqualTo(tenantId);
    }

    @Test
    void acceptance_byAnIndependentProvider_carriesNoTenant() {
        Booking booking = searchingBooking();

        assertThat(service.markProviderAccepted(booking.getId(), UUID.randomUUID()).getTenantId()).isNull();
    }

    @Test
    void acceptance_survivesAFailedTenantLookup() {
        Booking booking = searchingBooking();
        tenants.down = true;

        Booking result = service.markProviderAccepted(booking.getId(), UUID.randomUUID());

        assertThat(result.getStatus()).isEqualTo(BookingStatus.PROVIDER_ACCEPTED);
        assertThat(result.getTenantId()).isNull();
    }
}
