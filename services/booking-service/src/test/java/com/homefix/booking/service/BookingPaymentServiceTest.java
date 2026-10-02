package com.homefix.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingAudit;
import com.homefix.booking.domain.BookingStateMachine;
import com.homefix.booking.domain.BookingStatus;
import com.homefix.booking.service.BookingPaymentService.CompletionOutcome;
import com.homefix.booking.support.InMemoryBookingAuditRepository;
import com.homefix.booking.support.InMemoryBookingRepository;
import com.homefix.shared.outbox.OutboxEventPublisher;

/**
 * Tests for the booking's side of payment (Requirements 12.1, 12.6): the customer's request to pay
 * moving a finished job to PAYMENT_PENDING, and the {@code PaymentCompleted} event settling it.
 *
 * <p>The state machine, transition service and audit trail are real, so every assertion about the
 * audit chain is an assertion about what production writes; only persistence is in memory and the
 * transaction manager is a mock, which lets a test make a commit lose an optimistic-lock race.
 */
class BookingPaymentServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-03T09:30:00Z"), ZoneOffset.UTC);

    private InMemoryBookingRepository bookings;
    private InMemoryBookingAuditRepository audits;
    private OutboxEventPublisher outbox;
    private PlatformTransactionManager transactionManager;
    private BookingPaymentService service;

    @BeforeEach
    void setUp() {
        bookings = new InMemoryBookingRepository();
        audits = new InMemoryBookingAuditRepository();
        outbox = mock(OutboxEventPublisher.class);
        transactionManager = mock(PlatformTransactionManager.class);
        service = new BookingPaymentService(bookings,
                new BookingTransitionService(new BookingStateMachine(), audits,
                        new BookingLifecycleEventPublisher(outbox, CLOCK), CLOCK),
                transactionManager);
    }

    private Booking booking(BookingStatus status) {
        Booking b = Booking.create("HFX-20261003-PAY001", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), false, Instant.now(CLOCK), new BigDecimal("100.00"));
        b.setProviderId(UUID.randomUUID());
        b.applyStatus(status);
        return bookings.save(b);
    }

    /** A second, separately loaded copy of {@code original}, as a concurrent transaction sees it. */
    private Booking concurrentCopy(Booking original, BookingStatus status) {
        Booking copy = Booking.create(original.getReference(), original.getCustomerId(), original.getCategoryId(),
                original.getSubcategoryId(), original.getAddressId(), false, original.getScheduledAt(),
                original.getEstimatedTotal());
        ReflectionTestUtils.setField(copy, "id", original.getId());
        copy.applyStatus(status);
        return copy;
    }

    // ----- payment facts -----

    @Test
    void paymentFacts_returnsTheBooking() {
        Booking b = booking(BookingStatus.JOB_COMPLETED);

        assertThat(service.paymentFacts(b.getId())).isSameAs(b);
    }

    @Test
    void paymentFacts_unknownBookingIs404() {
        assertThatThrownBy(() -> service.paymentFacts(UUID.randomUUID()))
                .isInstanceOfSatisfying(BookingException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo("BOOKING_NOT_FOUND"));
    }

    // ----- payment pending (Requirement 12.1) -----

    @Test
    void pending_fromJobCompleted_passesThroughCustomerConfirmedWithTheCustomerAsActor() {
        Booking b = booking(BookingStatus.JOB_COMPLETED);

        Booking result = service.markPaymentPending(b.getId(), b.getCustomerId());

        assertThat(result.getStatus()).isEqualTo(BookingStatus.PAYMENT_PENDING);
        assertThat(audits.byBooking(b.getId()))
                .extracting(BookingAudit::getFromState, BookingAudit::getToState)
                .containsExactly(
                        tuple(BookingStatus.JOB_COMPLETED, BookingStatus.CUSTOMER_CONFIRMED),
                        tuple(BookingStatus.CUSTOMER_CONFIRMED, BookingStatus.PAYMENT_PENDING));
        assertThat(audits.byBooking(b.getId())).allSatisfy(a -> {
            assertThat(a.getActorId()).isEqualTo(b.getCustomerId());
            assertThat(a.getActorRole()).isEqualTo("CUSTOMER");
        });
        verify(transactionManager).commit(any());
    }

    @Test
    void pending_fromCustomerConfirmed_takesOneStep() {
        Booking b = booking(BookingStatus.CUSTOMER_CONFIRMED);

        service.markPaymentPending(b.getId(), b.getCustomerId());

        assertThat(b.getStatus()).isEqualTo(BookingStatus.PAYMENT_PENDING);
        assertThat(audits.byBooking(b.getId())).singleElement()
                .satisfies(a -> assertThat(a.getToState()).isEqualTo(BookingStatus.PAYMENT_PENDING));
    }

    @Test
    void pending_alreadyPending_isAnUnchangedRetry() {
        Booking b = booking(BookingStatus.PAYMENT_PENDING);

        Booking result = service.markPaymentPending(b.getId(), b.getCustomerId());

        assertThat(result.getStatus()).isEqualTo(BookingStatus.PAYMENT_PENDING);
        assertThat(audits.all()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, mode = EnumSource.Mode.EXCLUDE,
            names = {"JOB_COMPLETED", "CUSTOMER_CONFIRMED", "PAYMENT_PENDING"})
    void pending_fromAnyOtherState_is409NotPayable(BookingStatus status) {
        Booking b = booking(status);

        assertThatThrownBy(() -> service.markPaymentPending(b.getId(), b.getCustomerId()))
                .isInstanceOfSatisfying(BookingException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo("BOOKING_NOT_PAYABLE");
                    assertThat(ex.getStatus().value()).isEqualTo(409);
                });
        assertThat(b.getStatus()).isEqualTo(status);
        assertThat(audits.all()).isEmpty();
        verify(transactionManager).rollback(any());
    }

    @Test
    void pending_bySomeoneElse_isTheSame404AsAMissingBooking() {
        Booking b = booking(BookingStatus.JOB_COMPLETED);

        assertThatThrownBy(() -> service.markPaymentPending(b.getId(), UUID.randomUUID()))
                .isInstanceOfSatisfying(BookingException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo("BOOKING_NOT_FOUND"));
        assertThatThrownBy(() -> service.markPaymentPending(UUID.randomUUID(), b.getCustomerId()))
                .isInstanceOfSatisfying(BookingException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo("BOOKING_NOT_FOUND"));
        assertThat(b.getStatus()).isEqualTo(BookingStatus.JOB_COMPLETED);
    }

    @Test
    void pending_withoutCustomerIsAValidationError() {
        Booking b = booking(BookingStatus.JOB_COMPLETED);

        assertThatThrownBy(() -> service.markPaymentPending(b.getId(), null))
                .isInstanceOfSatisfying(BookingException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo("VALIDATION_ERROR"));
    }

    @Test
    void pending_losingARaceToAnotherPendingCall_retriesAndAnswersTheRetry() {
        Booking b = booking(BookingStatus.JOB_COMPLETED);
        // The first commit loses to a concurrent pending call; the retry re-reads PAYMENT_PENDING.
        doThrow(new ObjectOptimisticLockingFailureException(Booking.class, b.getId()))
                .doNothing()
                .when(transactionManager).commit(any());

        Booking result = service.markPaymentPending(b.getId(), b.getCustomerId());

        assertThat(result.getStatus()).isEqualTo(BookingStatus.PAYMENT_PENDING);
        verify(transactionManager, times(2)).commit(any());
    }

    @Test
    void pending_losingARaceToTheCompletionEvent_answersNotPayable() {
        Booking b = booking(BookingStatus.CUSTOMER_CONFIRMED);
        // The first commit loses because the consumer settled the booking meanwhile; the retry
        // reads the row the consumer committed.
        doAnswer(inv -> {
            bookings.save(concurrentCopy(b, BookingStatus.PAYMENT_COMPLETED));
            throw new ObjectOptimisticLockingFailureException(Booking.class, b.getId());
        }).doNothing().when(transactionManager).commit(any());

        assertThatThrownBy(() -> service.markPaymentPending(b.getId(), b.getCustomerId()))
                .isInstanceOfSatisfying(BookingException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo("BOOKING_NOT_PAYABLE"));
        verify(transactionManager).rollback(any());
    }

    @Test
    void pending_givesUpAfterRepeatedlyLosingTheRace() {
        Booking b = booking(BookingStatus.CUSTOMER_CONFIRMED);
        doThrow(new ObjectOptimisticLockingFailureException(Booking.class, b.getId()))
                .when(transactionManager).commit(any());

        assertThatThrownBy(() -> service.markPaymentPending(b.getId(), b.getCustomerId()))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        verify(transactionManager, times(BookingPaymentService.PENDING_ATTEMPTS)).commit(any());
    }

    // ----- payment completed (Requirement 12.6) -----

    @Test
    void completed_fromPaymentPending_settlesTheBookingAsTheSystem() {
        Booking b = booking(BookingStatus.PAYMENT_PENDING);
        UUID paymentId = UUID.randomUUID();

        assertThat(service.markPaymentCompleted(b.getId(), paymentId)).isEqualTo(CompletionOutcome.COMPLETED);

        assertThat(b.getStatus()).isEqualTo(BookingStatus.PAYMENT_COMPLETED);
        assertThat(audits.byBooking(b.getId())).singleElement().satisfies(a -> {
            assertThat(a.getFromState()).isEqualTo(BookingStatus.PAYMENT_PENDING);
            assertThat(a.getToState()).isEqualTo(BookingStatus.PAYMENT_COMPLETED);
            assertThat(a.getActorId()).isNull();
            assertThat(a.getActorRole()).isEqualTo("booking-service");
            assertThat(a.getReason()).contains(paymentId.toString());
        });
    }

    @Test
    void completed_fromJobCompleted_passesThroughBothIntermediateStates() {
        Booking b = booking(BookingStatus.JOB_COMPLETED);

        assertThat(service.markPaymentCompleted(b.getId(), UUID.randomUUID())).isEqualTo(CompletionOutcome.COMPLETED);

        assertThat(b.getStatus()).isEqualTo(BookingStatus.PAYMENT_COMPLETED);
        assertThat(audits.byBooking(b.getId())).extracting(BookingAudit::getToState).containsExactly(
                BookingStatus.CUSTOMER_CONFIRMED, BookingStatus.PAYMENT_PENDING, BookingStatus.PAYMENT_COMPLETED);
    }

    @Test
    void completed_fromCustomerConfirmed_passesThroughPaymentPending() {
        Booking b = booking(BookingStatus.CUSTOMER_CONFIRMED);

        service.markPaymentCompleted(b.getId(), UUID.randomUUID());

        assertThat(audits.byBooking(b.getId())).extracting(BookingAudit::getToState).containsExactly(
                BookingStatus.PAYMENT_PENDING, BookingStatus.PAYMENT_COMPLETED);
    }

    @Test
    void completed_alreadyCompleted_isANoOp() {
        Booking b = booking(BookingStatus.PAYMENT_COMPLETED);

        assertThat(service.markPaymentCompleted(b.getId(), UUID.randomUUID()))
                .isEqualTo(CompletionOutcome.ALREADY_COMPLETED);
        assertThat(audits.all()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, mode = EnumSource.Mode.EXCLUDE,
            names = {"JOB_COMPLETED", "CUSTOMER_CONFIRMED", "PAYMENT_PENDING", "PAYMENT_COMPLETED"})
    void completed_fromAStateAPaymentCannotSettle_changesNothingAndDoesNotThrow(BookingStatus status) {
        Booking b = booking(status);

        assertThat(service.markPaymentCompleted(b.getId(), UUID.randomUUID()))
                .isEqualTo(CompletionOutcome.NOT_PAYABLE);
        assertThat(b.getStatus()).isEqualTo(status);
        assertThat(audits.all()).isEmpty();
        verify(outbox, never()).publish(any(), any(), any(), any());
    }

    @Test
    void completed_unknownBooking_isReportedNotThrown() {
        assertThat(service.markPaymentCompleted(UUID.randomUUID(), UUID.randomUUID()))
                .isEqualTo(CompletionOutcome.UNKNOWN_BOOKING);
    }

    @Test
    void completed_runsInTheCallersTransactionNotItsOwnTemplate() {
        Booking b = booking(BookingStatus.PAYMENT_PENDING);

        service.markPaymentCompleted(b.getId(), UUID.randomUUID());

        // @Transactional joins the consumer's transaction in production; the retrying template is
        // the pending call's alone.
        verify(transactionManager, never()).getTransaction(any());
    }
}
