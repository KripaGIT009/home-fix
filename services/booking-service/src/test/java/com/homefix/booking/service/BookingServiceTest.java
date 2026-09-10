package com.homefix.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.homefix.booking.catalog.CatalogClientPort;
import com.homefix.booking.config.BookingProperties;
import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingAuditRepository;
import com.homefix.booking.domain.BookingRepository;
import com.homefix.booking.domain.BookingStateMachine;
import com.homefix.booking.domain.BookingStatus;
import com.homefix.booking.domain.SagaStep;
import com.homefix.booking.domain.SagaStepRepository;
import com.homefix.booking.event.BookingCreatedEvent;
import com.homefix.booking.media.MediaStoragePort;
import com.homefix.booking.pricing.PriceEstimate;
import com.homefix.booking.pricing.PricingClientPort;
import com.homefix.booking.pricing.PricingUnavailableException;
import com.homefix.shared.outbox.OutboxEventPublisher;

/**
 * Behavioural tests for the booking creation, confirmation, emergency, and cancellation flows
 * (Requirements 7, 8.1, 9.16-9.18). Collaborators are stubbed; the state machine, transition
 * service, saga orchestrator, and fee policy are real so the end-to-end behaviour is exercised.
 */
class BookingServiceTest {

    private static final Instant NOW = Instant.parse("2024-06-01T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private BookingRepository bookingRepository;
    private BookingAuditRepository auditRepository;
    private SagaStepRepository sagaStepRepository;
    private CatalogClientPort catalogClient;
    private PricingClientPort pricingClient;
    private OutboxEventPublisher outboxPublisher;
    private MediaStoragePort mediaStorage;

    private BookingService service;

    @BeforeEach
    void setUp() {
        bookingRepository = mock(BookingRepository.class);
        auditRepository = mock(BookingAuditRepository.class);
        sagaStepRepository = mock(SagaStepRepository.class);
        catalogClient = mock(CatalogClientPort.class);
        pricingClient = mock(PricingClientPort.class);
        outboxPublisher = mock(OutboxEventPublisher.class);
        mediaStorage = mock(MediaStoragePort.class);

        when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(bookingRepository.existsByReference(any())).thenReturn(false);
        when(auditRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sagaStepRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(catalogClient.isSubcategoryActive(any(), any())).thenReturn(true);
        when(outboxPublisher.publish(any(), any(), any(), any())).thenReturn(UUID.randomUUID());

        BookingProperties props = new BookingProperties();
        BookingStateMachine sm = new BookingStateMachine();
        BookingTransitionService transitionService =
                new BookingTransitionService(sm, auditRepository, CLOCK);
        BookingSagaOrchestrator saga = new BookingSagaOrchestrator(sagaStepRepository);
        BookingReferenceGenerator refGen = new BookingReferenceGenerator(bookingRepository, CLOCK);
        MediaService mediaService = new MediaService(mediaStorage,
                mock(com.homefix.booking.domain.JobMediaRepository.class), props);
        SubcategoryCancellationFeePort feePort = id -> Optional.empty();
        CancellationFeePolicy feePolicy = new CancellationFeePolicy(feePort, props);

        service = new BookingService(bookingRepository, catalogClient, pricingClient, mediaService,
                transitionService, saga, refGen, outboxPublisher, feePolicy, props, CLOCK);
    }

    private static PriceEstimate estimate() {
        return new PriceEstimate(new BigDecimal("799.00"),
                Map.of("basePrice", new BigDecimal("799.00")));
    }

    private CreateBookingCommand scheduledCmd(Instant scheduledAt) {
        return new CreateBookingCommand(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), false, scheduledAt, "leaky tap", List.of());
    }

    private CreateBookingCommand emergencyCmd() {
        return new CreateBookingCommand(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), true, null, "burst pipe", List.of());
    }

    // ----- scheduled create ------------------------------------------------

    @Test
    void scheduledCreateReturnsCreatedBookingWithItemizedEstimate() {
        when(pricingClient.estimate(any())).thenReturn(estimate());
        Instant scheduledAt = NOW.plus(Duration.ofHours(5));

        BookingService.BookingCreationResult result = service.createScheduled(scheduledCmd(scheduledAt));

        assertThat(result.booking().getStatus()).isEqualTo(BookingStatus.CREATED);
        assertThat(result.booking().getReference()).startsWith("HFX-");
        assertThat(result.estimate().total()).isEqualByComparingTo("799.00");
        // No event is published until confirmation (Requirement 7.5).
        verify(outboxPublisher, never()).publish(any(), any(), any(), any());
    }

    @Test
    void pricingEngineUnavailableReturns503AndCreatesNoBooking() {
        when(pricingClient.estimate(any())).thenThrow(new PricingUnavailableException("down"));
        Instant scheduledAt = NOW.plus(Duration.ofHours(5));

        assertThatThrownBy(() -> service.createScheduled(scheduledCmd(scheduledAt)))
                .isInstanceOf(BookingException.class)
                .satisfies(ex -> {
                    BookingException be = (BookingException) ex;
                    assertThat(be.getStatus().value()).isEqualTo(503);
                    assertThat(be.getErrorCode()).isEqualTo("PRICING_ENGINE_UNAVAILABLE");
                });

        verify(bookingRepository, never()).save(any());
    }

    @Test
    void leadTimeBelowTwoHoursIsRejectedBeforePricing() {
        Instant tooSoon = NOW.plus(Duration.ofMinutes(90));
        assertThatThrownBy(() -> service.createScheduled(scheduledCmd(tooSoon)))
                .isInstanceOf(BookingException.class)
                .satisfies(ex -> assertThat(((BookingException) ex).getErrorCode())
                        .isEqualTo("LEAD_TIME_TOO_SHORT"));
        verify(pricingClient, never()).estimate(any());
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void horizonBeyondNinetyDaysIsRejected() {
        Instant tooFar = NOW.plus(Duration.ofDays(91));
        assertThatThrownBy(() -> service.createScheduled(scheduledCmd(tooFar)))
                .isInstanceOf(BookingException.class)
                .satisfies(ex -> assertThat(((BookingException) ex).getErrorCode())
                        .isEqualTo("SCHEDULING_HORIZON_EXCEEDED"));
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void inactiveSubcategoryIsRejected() {
        when(catalogClient.isSubcategoryActive(any(), any())).thenReturn(false);
        Instant scheduledAt = NOW.plus(Duration.ofHours(5));
        assertThatThrownBy(() -> service.createScheduled(scheduledCmd(scheduledAt)))
                .isInstanceOf(BookingException.class)
                .satisfies(ex -> assertThat(((BookingException) ex).getErrorCode())
                        .isEqualTo("SERVICE_UNAVAILABLE"));
        verify(pricingClient, never()).estimate(any());
    }

    // ----- confirmation ----------------------------------------------------

    @Test
    void confirmTransitionsToSearchingProviderAndPublishesEvent() {
        when(pricingClient.estimate(any())).thenReturn(estimate());
        Instant scheduledAt = NOW.plus(Duration.ofHours(5));
        Booking created = service.createScheduled(scheduledCmd(scheduledAt)).booking();
        when(bookingRepository.findByReference(created.getReference())).thenReturn(Optional.of(created));

        Booking confirmed = service.confirm(created.getReference(),
                Actor.user(created.getCustomerId(), "CUSTOMER"));

        assertThat(confirmed.getStatus()).isEqualTo(BookingStatus.SEARCHING_PROVIDER);
        verify(outboxPublisher).publish(eq(BookingCreatedEvent.AGGREGATE_TYPE), eq(created.getId()),
                eq(BookingCreatedEvent.EVENT_TYPE), any());
        // The saga log recorded steps before proceeding (Requirement 24.6).
        verify(sagaStepRepository, org.mockito.Mockito.atLeastOnce()).save(any(SagaStep.class));
    }

    // ----- emergency -------------------------------------------------------

    @Test
    void emergencyCreateReachesSearchingProviderAndPublishesEvent() {
        when(pricingClient.estimate(any())).thenReturn(estimate());

        BookingService.BookingCreationResult result = service.createEmergency(emergencyCmd());

        assertThat(result.booking().isEmergency()).isTrue();
        assertThat(result.booking().getStatus()).isEqualTo(BookingStatus.SEARCHING_PROVIDER);
        verify(outboxPublisher).publish(eq(BookingCreatedEvent.AGGREGATE_TYPE), any(),
                eq(BookingCreatedEvent.EVENT_TYPE), any());
    }

    @Test
    void emergencyCreateCompletesWellWithinFiveSecondSla() {
        when(pricingClient.estimate(any())).thenReturn(estimate());
        long start = System.nanoTime();
        service.createEmergency(emergencyCmd());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        // Requirement 8.1: create within 2 s + SEARCHING_PROVIDER within 3 s = 5 s total.
        assertThat(elapsedMs).isLessThan(5_000);
    }

    @Test
    void emergencyPricingUnavailableReturns503AndCreatesNoBooking() {
        when(pricingClient.estimate(any())).thenThrow(new PricingUnavailableException("down"));
        assertThatThrownBy(() -> service.createEmergency(emergencyCmd()))
                .isInstanceOf(BookingException.class)
                .satisfies(ex -> assertThat(((BookingException) ex).getStatus().value()).isEqualTo(503));
        verify(bookingRepository, never()).save(any());
        verify(outboxPublisher, never()).publish(any(), any(), any(), any());
    }

    // ----- cancellation ----------------------------------------------------

    @Test
    void cancelBeforeProviderOnTheWayAppliesNoFee() {
        Booking booking = com.homefix.booking.support.Bookings.inState(BookingStatus.PROVIDER_ACCEPTED);
        when(bookingRepository.findByReference(booking.getReference())).thenReturn(Optional.of(booking));

        Booking cancelled = service.cancel(booking.getReference(),
                Actor.user(booking.getCustomerId(), "CUSTOMER"), "changed mind");

        assertThat(cancelled.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(cancelled.getCancellationFee()).isEqualByComparingTo("0.00");
    }

    @Test
    void cancelWhileProviderOnTheWayAppliesConfiguredFee() {
        BookingProperties props = new BookingProperties();
        props.setDefaultCancellationFee(new BigDecimal("75.00"));
        // Rebuild the service with a non-zero default fee.
        BookingStateMachine sm = new BookingStateMachine();
        BookingTransitionService transitionService =
                new BookingTransitionService(sm, auditRepository, CLOCK);
        CancellationFeePolicy feePolicy = new CancellationFeePolicy(id -> Optional.empty(), props);
        BookingService svc = new BookingService(bookingRepository, catalogClient, pricingClient,
                new MediaService(mediaStorage, mock(com.homefix.booking.domain.JobMediaRepository.class), props),
                transitionService, new BookingSagaOrchestrator(sagaStepRepository),
                new BookingReferenceGenerator(bookingRepository, CLOCK), outboxPublisher,
                feePolicy, props, CLOCK);

        Booking booking = com.homefix.booking.support.Bookings.inState(BookingStatus.PROVIDER_ON_THE_WAY);
        when(bookingRepository.findByReference(booking.getReference())).thenReturn(Optional.of(booking));

        Booking cancelled = svc.cancel(booking.getReference(),
                Actor.user(booking.getCustomerId(), "CUSTOMER"), "no longer needed");

        assertThat(cancelled.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(cancelled.getCancellationFee()).isEqualByComparingTo("75.00");
    }

    @Test
    void cancelFromNonCancellableStateIsRejected() {
        Booking booking = com.homefix.booking.support.Bookings.inState(BookingStatus.JOB_COMPLETED);
        when(bookingRepository.findByReference(booking.getReference())).thenReturn(Optional.of(booking));
        assertThatThrownBy(() -> service.cancel(booking.getReference(),
                Actor.user(booking.getCustomerId(), "CUSTOMER"), "x"))
                .isInstanceOf(BookingException.class);
    }
}
