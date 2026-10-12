package com.homefix.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import com.homefix.booking.address.CustomerAddressPort.ServiceAddress;
import com.homefix.booking.config.BookingProperties;
import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingAudit;
import com.homefix.booking.domain.BookingStateMachine;
import com.homefix.booking.domain.BookingStatus;
import com.homefix.booking.event.BookingCancelledEvent;
import com.homefix.booking.support.Bookings;
import com.homefix.booking.support.FakeTenantDirectory;
import com.homefix.booking.support.InMemoryBookingAuditRepository;
import com.homefix.booking.support.InMemoryBookingRepository;
import com.homefix.booking.support.InMemoryBookingTenantCandidateRepository;
import com.homefix.booking.support.MutableClock;
import com.homefix.booking.tenant.TenantDirectoryPort.CoveringTenant;
import com.homefix.shared.outbox.OutboxEventPublisher;

/**
 * A booking whose provider search was lost (review 17.5 item 4) is settled once it has been
 * SEARCHING_PROVIDER longer than the timeout, exactly as an exhausted search would be: the covering
 * Tenants' queue, or SEARCHING_FAILED with the customer told. A search still within the window, and a
 * booking that has moved on, are left alone.
 */
class StalledSearchSweeperTest {

    private static final Instant T0 = Instant.parse("2026-10-03T09:00:00Z");

    private InMemoryBookingRepository repository;
    private InMemoryBookingAuditRepository audits;
    private FakeTenantDirectory tenants;
    private OutboxEventPublisher outbox;
    private MutableClock clock;
    private StalledSearchSweeper sweeper;

    @BeforeEach
    void setUp() {
        audits = new InMemoryBookingAuditRepository();
        repository = new InMemoryBookingRepository().withAudits(audits);
        tenants = new FakeTenantDirectory();
        outbox = mock(OutboxEventPublisher.class);
        clock = new MutableClock(T0);
        BookingProperties properties = new BookingProperties();
        properties.setProviderSearchTimeout(Duration.ofMinutes(60));
        DispatchOutcomeService outcomes = new DispatchOutcomeService(repository,
                new BookingTransitionService(new BookingStateMachine(), audits,
                        new BookingLifecycleEventPublisher(outbox, clock), clock),
                new InMemoryBookingTenantCandidateRepository(),
                id -> Optional.of(new ServiceAddress("12 Station Rd, Ara", 25.556, 84.663)),
                tenants, TransactionOperations.withoutTransaction(), clock);
        sweeper = new StalledSearchSweeper(repository, outcomes, properties, clock);
    }

    /** A confirmed booking that entered SEARCHING_PROVIDER at {@code at}. */
    private Booking searchingSince(Instant at) {
        Booking booking = Bookings.inState(BookingStatus.SEARCHING_PROVIDER);
        repository.save(booking);
        audits.save(BookingAudit.of(booking.getId(), BookingStatus.CREATED, BookingStatus.SEARCHING_PROVIDER,
                booking.getCustomerId(), "CUSTOMER", at, "Customer confirmed booking"));
        return booking;
    }

    @Test
    void aLostSearchWithNoCoveringTenantFailsAndTheCustomerIsTold() {
        Booking booking = searchingSince(T0);
        clock.advance(Duration.ofMinutes(61));

        assertThat(sweeper.sweep()).isEqualTo(1);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.SEARCHING_FAILED);
        verify(outbox).publish(eq(BookingCancelledEvent.AGGREGATE_TYPE), eq(booking.getId()),
                eq(BookingCancelledEvent.EVENT_TYPE), any());
    }

    @Test
    void aLostSearchACoveringTenantCanTakeGoesToTheQueue() {
        Booking booking = searchingSince(T0);
        tenants.covering.add(new CoveringTenant(UUID.randomUUID(), "Ara Home Services", 1.5));
        clock.advance(Duration.ofMinutes(61));

        assertThat(sweeper.sweep()).isEqualTo(1);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.AWAITING_ASSIGNMENT);
        assertThat(booking.getQueuedForAssignmentAt()).isEqualTo(clock.instant());
        verify(outbox, never()).publish(any(), any(), any(), any());
    }

    @Test
    void aSearchStillWithinTheWindowIsLeftRunning() {
        Booking booking = searchingSince(T0);
        clock.advance(Duration.ofMinutes(60));

        assertThat(sweeper.sweep()).isZero();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.SEARCHING_PROVIDER);
    }

    @Test
    void aBookingThatWasAcceptedMeanwhileIsNotTouched() {
        Booking booking = searchingSince(T0);
        booking.applyStatus(BookingStatus.PROVIDER_ACCEPTED);
        clock.advance(Duration.ofHours(3));

        assertThat(sweeper.sweep()).isZero();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PROVIDER_ACCEPTED);
    }
}
