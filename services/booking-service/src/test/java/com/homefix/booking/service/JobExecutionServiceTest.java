package com.homefix.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.homefix.booking.config.BookingProperties;
import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingAuditRepository;
import com.homefix.booking.domain.BookingRepository;
import com.homefix.booking.domain.BookingStateMachine;
import com.homefix.booking.domain.BookingStatus;
import com.homefix.booking.domain.JobInterval;
import com.homefix.booking.domain.JobIntervalRepository;
import com.homefix.booking.domain.JobMediaRepository;
import com.homefix.booking.domain.PartsLineItem;
import com.homefix.booking.domain.PartsLineItemRepository;
import com.homefix.booking.event.JobExecutionEvents;
import com.homefix.booking.pricing.PartsRecalculationRequest;
import com.homefix.booking.pricing.PriceEstimate;
import com.homefix.booking.pricing.PricingClientPort;
import com.homefix.booking.support.Bookings;
import com.homefix.shared.outbox.OutboxEventPublisher;

/**
 * Unit tests for Task 15 job-execution milestones and parts/materials flow: before/after
 * photo gates (11.2, 9.10/11.4), pause reason validation and interval tracking (11.5), net
 * duration with multiple pauses (11.6), parts flow state transitions (6.8, 11.3), and the
 * additional-quote approval timeout (9.9). A real {@link BookingTransitionService} +
 * {@link BookingStateMachine} enforce the transition map; surrounding collaborators are mocked.
 */
@ExtendWith(MockitoExtension.class)
class JobExecutionServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2024-01-01T10:00:00Z"), ZoneOffset.UTC);
    private static final UUID PROVIDER = UUID.randomUUID();

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private BookingAuditRepository auditRepository;
    @Mock
    private JobIntervalRepository intervalRepository;
    @Mock
    private JobMediaRepository mediaRepository;
    @Mock
    private PartsLineItemRepository partsRepository;
    @Mock
    private PricingClientPort pricingClient;
    @Mock
    private OutboxEventPublisher outboxPublisher;

    private final List<JobInterval> savedIntervals = new ArrayList<>();

    private JobExecutionService newService() {
        return newService(CLOCK, new BookingProperties());
    }

    private JobExecutionService newService(Clock clock, BookingProperties props) {
        BookingTransitionService transition =
                new BookingTransitionService(new BookingStateMachine(), auditRepository, clock);
        lenient().when(auditRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(intervalRepository.save(any())).thenAnswer(inv -> {
            JobInterval saved = inv.getArgument(0);
            // Mimic JPA save semantics: identity-based upsert (re-saving a closed interval
            // must not create a duplicate row).
            if (!savedIntervals.contains(saved)) {
                savedIntervals.add(saved);
            }
            return saved;
        });
        lenient().when(intervalRepository.findByBookingIdAndEndedAtIsNull(any()))
                .thenAnswer(inv -> savedIntervals.stream().filter(JobInterval::isOpen).findFirst());
        lenient().when(intervalRepository.findByBookingIdOrderByStartedAtAsc(any()))
                .thenReturn(savedIntervals);
        return new JobExecutionService(bookingRepository, transition, intervalRepository,
                mediaRepository, partsRepository, pricingClient, outboxPublisher, props, clock);
    }

    private Booking bookingInState(BookingStatus status) {
        Booking b = Bookings.inState(status);
        b.setProviderId(PROVIDER);
        lenient().when(bookingRepository.findByReference(b.getReference())).thenReturn(Optional.of(b));
        return b;
    }

    // ----- before-photo gate (Requirement 11.2) ---------------------------

    @Test
    void startJobRejectedWithoutBeforePhoto() {
        JobExecutionService service = newService();
        Booking b = bookingInState(BookingStatus.PROVIDER_ARRIVED);
        when(mediaRepository.countByBookingIdAndType(b.getId(), JobExecutionService.BEFORE_PHOTO))
                .thenReturn(0L);

        assertThatThrownBy(() -> service.startJob(b.getReference(), providerActor()))
                .isInstanceOf(BookingException.class)
                .hasMessageContaining("before-photo");

        // No state change, no interval, no JobStarted event.
        assertThat(b.getStatus()).isEqualTo(BookingStatus.PROVIDER_ARRIVED);
        assertThat(savedIntervals).isEmpty();
        verify(outboxPublisher, never()).publish(any(), any(), any(), any());
    }

    @Test
    void startJobSucceedsWithBeforePhotoAndPublishesJobStarted() {
        JobExecutionService service = newService();
        Booking b = bookingInState(BookingStatus.PROVIDER_ARRIVED);
        when(mediaRepository.countByBookingIdAndType(b.getId(), JobExecutionService.BEFORE_PHOTO))
                .thenReturn(1L);

        service.startJob(b.getReference(), providerActor());

        assertThat(b.getStatus()).isEqualTo(BookingStatus.JOB_STARTED);
        assertThat(b.getStartedAt()).isEqualTo(Instant.now(CLOCK));
        assertThat(savedIntervals).hasSize(1);
        assertThat(savedIntervals.get(0).getKind()).isEqualTo(JobInterval.Kind.WORK);
        verify(outboxPublisher).publish(eq(JobExecutionEvents.AGGREGATE_TYPE), eq(b.getId()),
                eq(JobExecutionEvents.JobStarted.EVENT_TYPE), any());
    }

    // ----- after-photo gate (Requirement 9.10, 9.11, 11.4) ----------------

    @Test
    void completeJobRejectedWithoutAfterPhoto() {
        JobExecutionService service = newService();
        Booking b = bookingInState(BookingStatus.JOB_STARTED);
        when(mediaRepository.countByBookingIdAndType(b.getId(), JobExecutionService.AFTER_PHOTO))
                .thenReturn(0L);

        assertThatThrownBy(() -> service.completeJob(b.getReference(), providerActor()))
                .isInstanceOf(BookingException.class)
                .hasMessageContaining("after-photo");

        assertThat(b.getStatus()).isEqualTo(BookingStatus.JOB_STARTED);
        verify(outboxPublisher, never()).publish(any(), any(), any(), any());
    }

    @Test
    void completeJobSucceedsWithAfterPhotoAndPublishesJobCompleted() {
        JobExecutionService service = newService();
        Booking b = bookingInState(BookingStatus.JOB_STARTED);
        b.setStartedAt(Instant.now(CLOCK).minusSeconds(600));
        savedIntervals.add(openWork(Instant.now(CLOCK).minusSeconds(600)));
        when(mediaRepository.countByBookingIdAndType(b.getId(), JobExecutionService.AFTER_PHOTO))
                .thenReturn(1L);

        service.completeJob(b.getReference(), providerActor());

        assertThat(b.getStatus()).isEqualTo(BookingStatus.JOB_COMPLETED);
        assertThat(b.getCompletedAt()).isEqualTo(Instant.now(CLOCK));
        assertThat(b.getNetDurationSeconds()).isEqualTo(600);
        verify(outboxPublisher).publish(eq(JobExecutionEvents.AGGREGATE_TYPE), eq(b.getId()),
                eq(JobExecutionEvents.JobCompleted.EVENT_TYPE), any());
    }

    // ----- pause reason validation & interval tracking (Requirement 11.5) --

    @Test
    void pauseRejectedWhenReasonBlank() {
        JobExecutionService service = newService();
        Booking b = bookingInState(BookingStatus.JOB_STARTED);
        assertThatThrownBy(() -> service.pauseJob(b.getReference(), providerActor(), "  "))
                .isInstanceOf(BookingException.class)
                .hasMessageContaining("reason is required");
        assertThat(b.getStatus()).isEqualTo(BookingStatus.JOB_STARTED);
    }

    @Test
    void pauseRejectedWhenReasonExceeds500Chars() {
        JobExecutionService service = newService();
        Booking b = bookingInState(BookingStatus.JOB_STARTED);
        String tooLong = "x".repeat(501);
        assertThatThrownBy(() -> service.pauseJob(b.getReference(), providerActor(), tooLong))
                .isInstanceOf(BookingException.class)
                .hasMessageContaining("at most 500");
    }

    @Test
    void pauseClosesWorkIntervalAndOpensPauseInterval() {
        JobExecutionService service = newService();
        Booking b = bookingInState(BookingStatus.JOB_STARTED);
        savedIntervals.add(openWork(Instant.now(CLOCK).minusSeconds(300)));

        service.pauseJob(b.getReference(), providerActor(), "Waiting for a spare part");

        assertThat(b.getStatus()).isEqualTo(BookingStatus.JOB_PAUSED);
        assertThat(savedIntervals.get(0).isOpen()).isFalse(); // work closed
        JobInterval pause = savedIntervals.get(savedIntervals.size() - 1);
        assertThat(pause.getKind()).isEqualTo(JobInterval.Kind.PAUSE);
        assertThat(pause.getReason()).isEqualTo("Waiting for a spare part");
        assertThat(pause.isOpen()).isTrue();
    }

    @Test
    void resumeClosesPauseAndOpensNewWorkInterval() {
        JobExecutionService service = newService();
        Booking b = bookingInState(BookingStatus.JOB_PAUSED);
        JobInterval openPause = JobInterval.pause(b.getId(), Instant.now(CLOCK).minusSeconds(120), "break");
        savedIntervals.add(openPause);

        service.resumeJob(b.getReference(), providerActor());

        assertThat(b.getStatus()).isEqualTo(BookingStatus.JOB_STARTED);
        assertThat(openPause.isOpen()).isFalse();
        JobInterval last = savedIntervals.get(savedIntervals.size() - 1);
        assertThat(last.getKind()).isEqualTo(JobInterval.Kind.WORK);
        assertThat(last.isOpen()).isTrue();
    }

    // ----- net duration across multiple pauses (Requirement 11.6) ----------

    @Test
    void netDurationSumsWorkMinusPauseAcrossMultiplePauses() {
        JobExecutionService service = newService();
        Booking b = bookingInState(BookingStatus.JOB_STARTED);
        Instant now = Instant.now(CLOCK);
        // WORK 10m, PAUSE 5m, WORK 10m closed; a final open WORK of 10m closed at completion.
        savedIntervals.add(closedWork(now.minusSeconds(3000), now.minusSeconds(2400))); // 600
        savedIntervals.add(closedPause(now.minusSeconds(2400), now.minusSeconds(2100))); // 300
        savedIntervals.add(closedWork(now.minusSeconds(2100), now.minusSeconds(1500))); // 600
        savedIntervals.add(openWork(now.minusSeconds(600)));                            // 600 at completion
        b.setStartedAt(now.minusSeconds(3000));
        when(mediaRepository.countByBookingIdAndType(b.getId(), JobExecutionService.AFTER_PHOTO))
                .thenReturn(1L);

        service.completeJob(b.getReference(), providerActor());

        // work = 600+600+600 = 1800; pause = 300; net = 1500
        assertThat(b.getNetDurationSeconds()).isEqualTo(1500);
    }

    // ----- parts flow state transitions (Requirement 6.8, 11.3) -----------

    @Test
    void addPartsRecalculatesAndTransitionsToCustomerApprovalPending() {
        JobExecutionService service = newService();
        Booking b = bookingInState(BookingStatus.JOB_STARTED);
        // parts total = 2 x 150.00 = 300.00; updated total = original 100.00 + 300.00
        when(partsRepository.findByBookingIdOrderByAddedAtAsc(b.getId()))
                .thenReturn(List.of(PartsLineItem.of(b.getId(), "Valve", 2, new BigDecimal("150.00"),
                        Instant.now(CLOCK))));
        when(pricingClient.recalculateWithParts(any(PartsRecalculationRequest.class)))
                .thenReturn(new PriceEstimate(new BigDecimal("400.00"), java.util.Map.of()));

        service.addParts(b.getReference(), providerActor(),
                new AddPartsCommand("Valve", 2, new BigDecimal("150.00")));

        assertThat(b.getStatus()).isEqualTo(BookingStatus.CUSTOMER_APPROVAL_PENDING);
        assertThat(b.getFinalTotal()).isEqualByComparingTo("400.00");
        verify(partsRepository).save(any(PartsLineItem.class));

        ArgumentCaptor<PartsRecalculationRequest> captor =
                ArgumentCaptor.forClass(PartsRecalculationRequest.class);
        verify(pricingClient).recalculateWithParts(captor.capture());
        assertThat(captor.getValue().partsTotal()).isEqualByComparingTo("300.00");
    }

    @Test
    void addPartsRejectsQuantityBelowOne() {
        JobExecutionService service = newService();
        bookingInState(BookingStatus.JOB_STARTED);
        assertThatThrownBy(() -> service.addParts("HFX-20240101-ABC123", providerActor(),
                new AddPartsCommand("Valve", 0, new BigDecimal("150.00"))))
                .isInstanceOf(BookingException.class)
                .hasMessageContaining("quantity");
    }

    @Test
    void addPartsRejectsUnitCostBelowMinimum() {
        JobExecutionService service = newService();
        bookingInState(BookingStatus.JOB_STARTED);
        assertThatThrownBy(() -> service.addParts("HFX-20240101-ABC123", providerActor(),
                new AddPartsCommand("Valve", 1, new BigDecimal("0.00"))))
                .isInstanceOf(BookingException.class)
                .hasMessageContaining("unit cost");
    }

    @Test
    void approveAdditionalQuoteReturnsToJobStarted() {
        JobExecutionService service = newService();
        Booking b = bookingInState(BookingStatus.CUSTOMER_APPROVAL_PENDING);
        service.approveAdditionalQuote(b.getReference(), customerActor());
        assertThat(b.getStatus()).isEqualTo(BookingStatus.JOB_STARTED);
        assertThat(savedIntervals.get(savedIntervals.size() - 1).getKind())
                .isEqualTo(JobInterval.Kind.WORK);
    }

    @Test
    void rejectAdditionalQuoteCompletesAtOriginalPrice() {
        JobExecutionService service = newService();
        Booking b = bookingInState(BookingStatus.CUSTOMER_APPROVAL_PENDING);
        b.setFinalTotal(new BigDecimal("400.00")); // updated total pending approval

        service.rejectAdditionalQuote(b.getReference(), customerActor());

        assertThat(b.getStatus()).isEqualTo(BookingStatus.JOB_COMPLETED);
        assertThat(b.getFinalTotal()).isEqualByComparingTo(b.getEstimatedTotal());
        verify(outboxPublisher).publish(any(), eq(b.getId()),
                eq(JobExecutionEvents.JobCompleted.EVENT_TYPE), any());
    }

    // ----- approval timeout (Requirement 9.9) ------------------------------

    @Test
    void approvalTimeoutAutoCompletesAtOriginalPriceAfter60Minutes() {
        BookingProperties props = new BookingProperties();
        Instant pendingSince = Instant.parse("2024-01-01T09:00:00Z");
        // Clock is 61 minutes after pendingSince => past the 60-minute deadline.
        Clock late = Clock.fixed(pendingSince.plus(Duration.ofMinutes(61)), ZoneOffset.UTC);
        JobExecutionService service = newService(late, props);
        Booking b = bookingInState(BookingStatus.CUSTOMER_APPROVAL_PENDING);
        b.setFinalTotal(new BigDecimal("400.00"));

        boolean resolved = service.autoResolveApprovalTimeout(b.getReference(), pendingSince);

        assertThat(resolved).isTrue();
        assertThat(b.getStatus()).isEqualTo(BookingStatus.JOB_COMPLETED);
        assertThat(b.getFinalTotal()).isEqualByComparingTo(b.getEstimatedTotal());
    }

    @Test
    void approvalTimeoutIsNoopBeforeDeadline() {
        BookingProperties props = new BookingProperties();
        Instant pendingSince = Instant.parse("2024-01-01T09:00:00Z");
        // 59 minutes after pendingSince => still within the window.
        Clock early = Clock.fixed(pendingSince.plus(Duration.ofMinutes(59)), ZoneOffset.UTC);
        JobExecutionService service = newService(early, props);
        Booking b = bookingInState(BookingStatus.CUSTOMER_APPROVAL_PENDING);

        boolean resolved = service.autoResolveApprovalTimeout(b.getReference(), pendingSince);

        assertThat(resolved).isFalse();
        assertThat(b.getStatus()).isEqualTo(BookingStatus.CUSTOMER_APPROVAL_PENDING);
        verify(outboxPublisher, never()).publish(any(), any(), any(), any());
    }

    // ----- helpers ---------------------------------------------------------

    private static Actor providerActor() {
        return Actor.user(PROVIDER, "SERVICE_PROVIDER");
    }

    private static Actor customerActor() {
        return Actor.user(UUID.randomUUID(), "CUSTOMER");
    }

    private static JobInterval openWork(Instant start) {
        return JobInterval.work(UUID.randomUUID(), start);
    }

    private static JobInterval closedWork(Instant start, Instant end) {
        JobInterval i = JobInterval.work(UUID.randomUUID(), start);
        i.close(end);
        return i;
    }

    private static JobInterval closedPause(Instant start, Instant end) {
        JobInterval i = JobInterval.pause(UUID.randomUUID(), start, "break");
        i.close(end);
        return i;
    }
}
