package com.homefix.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import com.homefix.booking.config.BookingProperties;
import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingAudit;
import com.homefix.booking.domain.BookingStateMachine;
import com.homefix.booking.domain.BookingStatus;
import com.homefix.booking.domain.JobIntervalRepository;
import com.homefix.booking.domain.JobMediaRepository;
import com.homefix.booking.domain.PartsLineItemRepository;
import com.homefix.booking.pricing.PricingClientPort;
import com.homefix.booking.support.Bookings;
import com.homefix.booking.support.InMemoryBookingAuditRepository;
import com.homefix.booking.support.InMemoryBookingRepository;
import com.homefix.booking.support.MutableClock;
import com.homefix.shared.outbox.OutboxEventPublisher;

/**
 * The additional-quote approval timeout (Requirement 9.9), now actually scheduled: an unanswered
 * quote is completed at the original price once the window has passed since the booking (most
 * recently) entered CUSTOMER_APPROVAL_PENDING, by the system, and an answered one is left alone.
 */
class QuoteApprovalTimeoutSweeperTest {

    private static final Instant T0 = Instant.parse("2026-10-03T09:00:00Z");

    private InMemoryBookingRepository repository;
    private InMemoryBookingAuditRepository audits;
    private MutableClock clock;
    private QuoteApprovalTimeoutSweeper sweeper;

    @BeforeEach
    void setUp() {
        audits = new InMemoryBookingAuditRepository();
        repository = new InMemoryBookingRepository().withAudits(audits);
        clock = new MutableClock(T0);
        BookingProperties properties = new BookingProperties();
        properties.setAdditionalQuoteTimeout(Duration.ofMinutes(60));
        BookingTransitionService transitions = new BookingTransitionService(new BookingStateMachine(), audits,
                new BookingLifecycleEventPublisher(mock(OutboxEventPublisher.class), clock), clock);
        JobExecutionService jobExecution = new JobExecutionService(repository, transitions,
                mock(JobIntervalRepository.class), mock(JobMediaRepository.class),
                mock(PartsLineItemRepository.class), mock(PricingClientPort.class),
                mock(OutboxEventPublisher.class), properties, clock);
        sweeper = new QuoteApprovalTimeoutSweeper(repository, audits, jobExecution,
                TransactionOperations.withoutTransaction(), properties, clock);
    }

    /** A job whose provider asked for an additional quote at {@code at}, now at 400.00 pending approval. */
    private Booking quotePendingSince(Instant at) {
        Booking booking = Bookings.inState(BookingStatus.CUSTOMER_APPROVAL_PENDING);
        booking.setFinalTotal(new BigDecimal("400.00"));
        repository.save(booking);
        enteredApprovalAt(booking, at);
        return booking;
    }

    private void enteredApprovalAt(Booking booking, Instant at) {
        audits.save(BookingAudit.of(booking.getId(), BookingStatus.ADDITIONAL_QUOTE_REQUIRED,
                BookingStatus.CUSTOMER_APPROVAL_PENDING, null, "booking-service", at, "Awaiting approval"));
    }

    @Test
    void anUnansweredQuoteIsCompletedAtTheOriginalPriceBySystem() {
        Booking booking = quotePendingSince(T0);
        clock.advance(Duration.ofMinutes(61));

        assertThat(sweeper.sweep()).isEqualTo(1);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.JOB_COMPLETED);
        assertThat(booking.getFinalTotal()).isEqualByComparingTo(booking.getEstimatedTotal());
        BookingAudit completion = audits.byBooking(booking.getId()).stream()
                .filter(a -> a.getToState() == BookingStatus.JOB_COMPLETED).findFirst().orElseThrow();
        assertThat(completion.getActorId()).isNull();
        assertThat(completion.getActorRole()).isEqualTo("booking-service");
    }

    @Test
    void aQuoteStillWithinTheWindowIsLeftForALaterPass() {
        Booking booking = quotePendingSince(T0);
        clock.advance(Duration.ofMinutes(60));

        assertThat(sweeper.sweep()).isZero();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CUSTOMER_APPROVAL_PENDING);

        clock.advance(Duration.ofSeconds(1));
        assertThat(sweeper.sweep()).isEqualTo(1);
    }

    @Test
    void theWindowRunsFromTheLatestQuote() {
        // A first quote at T0 was approved; a second parts request at T0+50m starts a new wait.
        Booking booking = quotePendingSince(T0);
        enteredApprovalAt(booking, T0.plus(Duration.ofMinutes(50)));
        clock.advance(Duration.ofMinutes(61));

        assertThat(sweeper.sweep()).isZero();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CUSTOMER_APPROVAL_PENDING);
    }

    @Test
    void anAnsweredQuoteIsNotTouched() {
        Booking booking = quotePendingSince(T0);
        booking.applyStatus(BookingStatus.JOB_STARTED); // the customer approved
        clock.advance(Duration.ofMinutes(120));

        assertThat(sweeper.sweep()).isZero();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.JOB_STARTED);
    }
}
