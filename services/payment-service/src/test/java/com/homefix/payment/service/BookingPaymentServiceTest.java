package com.homefix.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.homefix.payment.alert.FinanceAlertPort;
import com.homefix.payment.booking.BookingClientPort;
import com.homefix.payment.booking.BookingPaymentFacts;
import com.homefix.payment.config.PaymentProperties;
import com.homefix.payment.crypto.LocalAesKmsAdapter;
import com.homefix.payment.domain.PaymentMethod;
import com.homefix.payment.domain.PaymentTransaction;
import com.homefix.payment.domain.TransactionStatus;
import com.homefix.payment.gateway.AbstractHmacGatewayAdapter;
import com.homefix.payment.gateway.GatewayChargeRequest;
import com.homefix.payment.gateway.GatewayChargeResult;
import com.homefix.payment.gateway.HmacSignatures;
import com.homefix.payment.gateway.PaymentGatewayRegistry;
import com.homefix.payment.gateway.RazorpayGatewayAdapter;
import com.homefix.payment.gateway.SimulatorGatewayAdapter;
import com.homefix.payment.idempotency.InMemoryIdempotencyStoreAdapter;
import com.homefix.payment.invoice.InvoiceTriggerPort;
import com.homefix.payment.notification.ProviderNotificationPort;
import com.homefix.payment.support.InMemoryPaymentRefundRepository;
import com.homefix.payment.support.InMemoryPaymentTransactionRepository;
import com.homefix.payment.support.InMemorySettlementRepository;
import com.homefix.payment.support.MarkingTransactionOperations;
import com.homefix.payment.support.RecordingPaymentCompletedPublisher;
import com.homefix.payment.wallet.ProviderWalletClientPort;

/**
 * Tests the booking payment flow behind {@code POST /payments} (contract items 4-6; Requirement
 * 12.2, 12.3, 12.10): pricing from the Booking Service's facts only, ownership, the payable-status
 * gate, fail-closed behaviour when the Booking Service is down, idempotency after success, a new
 * attempt after a failure, and the local simulator settling end to end.
 *
 * <p>Runs the real {@link PaymentService} against the same in-memory fakes as
 * {@code PaymentServiceTest}, with a fake {@link BookingClientPort} that behaves like the Booking
 * Service's internal endpoints. No Spring context, database or network.
 */
class BookingPaymentServiceTest {

    private static final String TEST_DATA_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String RAZORPAY_SECRET = "razorpay-test-secret";

    private final UUID customerId = UUID.randomUUID();
    private final UUID providerId = UUID.randomUUID();
    private final UUID bookingId = UUID.randomUUID();

    private InMemoryPaymentTransactionRepository transactionRepository;
    private RecordingPaymentCompletedPublisher publisher;
    private CountingRazorpay razorpay;
    private PaymentProperties props;
    private FakeBookingClient bookings;
    private BookingPaymentService service;

    @BeforeEach
    void setUp() {
        transactionRepository = new InMemoryPaymentTransactionRepository();
        publisher = new RecordingPaymentCompletedPublisher();
        razorpay = new CountingRazorpay();
        props = new PaymentProperties();
        props.setRetryBackoff(Duration.ZERO);
        bookings = new FakeBookingClient(new BookingPaymentFacts(bookingId, "HF-1001", customerId, providerId,
                "JOB_COMPLETED", new BigDecimal("499.00"), "INR"));
        service = newService(new PaymentGatewayRegistry(List.of(razorpay)));
    }

    private BookingPaymentService newService(PaymentGatewayRegistry registry) {
        PaymentService payments = new PaymentService(transactionRepository, new InMemoryPaymentRefundRepository(),
                new InMemorySettlementRepository(), registry, new InMemoryIdempotencyStoreAdapter(),
                new LocalAesKmsAdapter(TEST_DATA_KEY), mock(ProviderWalletClientPort.class),
                mock(InvoiceTriggerPort.class), publisher, mock(FinanceAlertPort.class),
                mock(ProviderNotificationPort.class), props, new MarkingTransactionOperations());
        return new BookingPaymentService(bookings, payments, registry, props);
    }

    private PaymentTransaction payAsCustomer() {
        return service.payForBooking(new PayBookingCommand(bookingId, PaymentMethod.UPI, null, null),
                customerId, false);
    }

    private static void assertPaymentError(Throwable ex, HttpStatus status, String code) {
        assertThat(ex).isInstanceOf(PaymentException.class);
        PaymentException pe = (PaymentException) ex;
        assertThat(pe.getStatus()).isEqualTo(status);
        assertThat(pe.getErrorCode()).isEqualTo(code);
    }

    // ------------------------------------------------------------------ pricing and ownership

    @Test
    void initiatesFromTheBookingFacts_andMovesTheBookingToPaymentPendingFirst() {
        PaymentTransaction tx = payAsCustomer();

        assertThat(tx.getCustomerId()).isEqualTo(customerId);
        assertThat(tx.getBookingId()).isEqualTo(bookingId);
        assertThat(tx.getProviderId()).isEqualTo(providerId);
        assertThat(tx.getAmount()).isEqualByComparingTo("499.00");
        // Platform fee from the default 20% rule, never from the client.
        assertThat(tx.getPlatformFee()).isEqualByComparingTo("99.80");
        assertThat(tx.getGateway()).isEqualTo(RazorpayGatewayAdapter.GATEWAY_ID);
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(bookings.pendingCustomers).containsExactly(customerId);
        assertThat(bookings.facts.status()).isEqualTo("PAYMENT_PENDING");
        assertThat(bookings.chargedWhilePending).containsExactly(true);
        // Recorded so the provider's wallet credit can name the job.
        assertThat(tx.getBookingReference()).isEqualTo("HF-1001");
    }

    @Test
    void staffPayOnTheCustomersBehalf_andTheTransactionNamesTheBookingsCustomer() {
        PaymentTransaction tx = service.payForBooking(
                new PayBookingCommand(bookingId, PaymentMethod.CASH, null, null), null, true);

        assertThat(tx.getCustomerId()).isEqualTo(customerId);
        assertThat(bookings.pendingCustomers).containsExactly(customerId);
    }

    @Test
    void anotherCustomersBooking_is404_andNothingMovesOrIsCharged() {
        assertThatThrownBy(() -> service.payForBooking(
                new PayBookingCommand(bookingId, PaymentMethod.UPI, null, null), UUID.randomUUID(), false))
                .satisfies(ex -> assertPaymentError(ex, HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND"));

        assertThat(bookings.pendingCustomers).isEmpty();
        assertThat(razorpay.charges).isZero();
        assertThat(transactionRepository.findAll()).isEmpty();
    }

    @Test
    void unknownBooking_is404() {
        bookings.facts = null;

        assertThatThrownBy(this::payAsCustomer)
                .satisfies(ex -> assertPaymentError(ex, HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND"));
        assertThat(razorpay.charges).isZero();
    }

    @Test
    void bookingNotYetCompleted_is409_andNothingMovesOrIsCharged() {
        bookings.setStatus("IN_PROGRESS");

        assertThatThrownBy(this::payAsCustomer)
                .satisfies(ex -> assertPaymentError(ex, HttpStatus.CONFLICT, "BOOKING_NOT_PAYABLE"));
        assertThat(bookings.pendingCustomers).isEmpty();
        assertThat(razorpay.charges).isZero();
    }

    @Test
    void customerConfirmedAndPaymentPendingBookings_arePayable() {
        bookings.setStatus("CUSTOMER_CONFIRMED");
        assertThat(payAsCustomer().getStatus()).isEqualTo(TransactionStatus.PENDING);

        transactionRepository.deleteAll();
        bookings.setStatus("PAYMENT_PENDING");
        assertThat(payAsCustomer().getStatus()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void unknownGateway_isRejectedBeforeTheBookingMoves() {
        assertThatThrownBy(() -> service.payForBooking(
                new PayBookingCommand(bookingId, PaymentMethod.UPI, "paypal", null), customerId, false))
                .satisfies(ex -> assertPaymentError(ex, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR"));
        assertThat(bookings.pendingCustomers).isEmpty();
    }

    // ------------------------------------------------------------------ Booking Service down

    @Test
    void bookingServiceDownOnTheFactsRead_is503_andNothingIsCharged() {
        bookings.factsError = unavailable();

        assertThatThrownBy(this::payAsCustomer)
                .satisfies(ex -> assertPaymentError(ex, HttpStatus.SERVICE_UNAVAILABLE, "BOOKING_SERVICE_UNAVAILABLE"));
        assertThat(razorpay.charges).isZero();
        assertThat(transactionRepository.findAll()).isEmpty();
    }

    @Test
    void bookingServiceDownOnTheMoveToPaymentPending_is503_andNothingIsCharged() {
        bookings.pendingError = unavailable();

        assertThatThrownBy(this::payAsCustomer)
                .satisfies(ex -> assertPaymentError(ex, HttpStatus.SERVICE_UNAVAILABLE, "BOOKING_SERVICE_UNAVAILABLE"));
        assertThat(razorpay.charges).isZero();
        assertThat(transactionRepository.findAll()).isEmpty();
    }

    // ------------------------------------------------------------------ idempotency and new attempts

    @Test
    void secondPostAfterSuccess_returnsThePayment_evenThoughTheBookingIsNowPaymentCompleted() {
        PaymentTransaction first = payAsCustomer();
        String payload = "{\"eventId\":\"evt_1\",\"transactionId\":\"" + first.getId() + "\","
                + "\"gatewayId\":\"razorpay\",\"status\":\"SUCCEEDED\",\"amount\":\"499.00\","
                + "\"timestamp\":\"" + Instant.now() + "\"}";
        PaymentTransaction succeeded = newPaymentService().handleGatewayCallback(first.getId(),
                new GatewayCallback("razorpay", payload, HmacSignatures.hmacSha256Hex(RAZORPAY_SECRET, payload)));
        assertThat(succeeded.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        bookings.setStatus("PAYMENT_COMPLETED");

        PaymentTransaction again = payAsCustomer();

        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(again.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(razorpay.charges).isEqualTo(1);
        assertThat(bookings.pendingCustomers).hasSize(1);
    }

    @Test
    void afterAFailedPayment_aNewPostStartsANewAttempt() {
        razorpay.accept = false;
        PaymentTransaction failed = payAsCustomer();
        assertThat(failed.getStatus()).isEqualTo(TransactionStatus.FAILED);
        // The booking stays PAYMENT_PENDING after a failed charge, so it is still payable.
        assertThat(bookings.facts.status()).isEqualTo("PAYMENT_PENDING");

        razorpay.accept = true;
        PaymentTransaction retry = payAsCustomer();

        assertThat(retry.getId()).isNotEqualTo(failed.getId());
        assertThat(retry.getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(razorpay.charges).isEqualTo(2);
    }

    // ------------------------------------------------------------------ local simulator

    @Test
    void simulatorDefaultGateway_settlesToSuccess_andPublishesPaymentCompleted() {
        props.setDefaultGateway(SimulatorGatewayAdapter.GATEWAY_ID);
        service = newService(new PaymentGatewayRegistry(
                List.of(razorpay, new SimulatorGatewayAdapter("simulator-test-secret"))));

        PaymentTransaction tx = payAsCustomer();

        assertThat(tx.getGateway()).isEqualTo(SimulatorGatewayAdapter.GATEWAY_ID);
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(publisher.published()).singleElement().satisfies(event -> {
            assertThat(event.getId()).isEqualTo(tx.getId());
            assertThat(event.getBookingId()).isEqualTo(bookingId);
            assertThat(event.getProviderId()).isEqualTo(providerId);
        });
    }

    @Test
    void simulatorNotRegistered_meansTheDefaultGatewayIsUnknown() {
        props.setDefaultGateway(SimulatorGatewayAdapter.GATEWAY_ID);

        assertThatThrownBy(this::payAsCustomer)
                .satisfies(ex -> assertPaymentError(ex, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR"));
        assertThat(bookings.pendingCustomers).isEmpty();
    }

    // ------------------------------------------------------------------ helpers / fakes

    /** A second PaymentService over the same repository, standing in for the callback request. */
    private PaymentService newPaymentService() {
        return new PaymentService(transactionRepository, new InMemoryPaymentRefundRepository(),
                new InMemorySettlementRepository(), new PaymentGatewayRegistry(List.of(razorpay)),
                new InMemoryIdempotencyStoreAdapter(), new LocalAesKmsAdapter(TEST_DATA_KEY),
                mock(ProviderWalletClientPort.class), mock(InvoiceTriggerPort.class), publisher,
                mock(FinanceAlertPort.class), mock(ProviderNotificationPort.class), props,
                new MarkingTransactionOperations());
    }

    private static PaymentException unavailable() {
        return new PaymentException(HttpStatus.SERVICE_UNAVAILABLE, "BOOKING_SERVICE_UNAVAILABLE", "down");
    }

    /** Behaves like the Booking Service's internal payment-facts / payment-pending endpoints. */
    private final class FakeBookingClient implements BookingClientPort {
        BookingPaymentFacts facts;
        RuntimeException factsError;
        RuntimeException pendingError;
        final List<UUID> pendingCustomers = new ArrayList<>();
        final List<Boolean> chargedWhilePending = new ArrayList<>();

        FakeBookingClient(BookingPaymentFacts facts) {
            this.facts = facts;
        }

        void setStatus(String status) {
            facts = new BookingPaymentFacts(facts.bookingId(), facts.reference(), facts.customerId(),
                    facts.providerId(), status, facts.amount(), facts.currency());
        }

        @Override
        public Optional<BookingPaymentFacts> paymentFacts(UUID id) {
            if (factsError != null) {
                throw factsError;
            }
            return Optional.ofNullable(facts).filter(f -> f.bookingId().equals(id));
        }

        @Override
        public BookingPaymentFacts markPaymentPending(UUID id, UUID customer) {
            pendingCustomers.add(customer);
            if (pendingError != null) {
                throw pendingError;
            }
            if (facts == null || !facts.customerId().equals(customer)) {
                throw PaymentException.bookingNotFound(id);
            }
            if (!facts.isPayable()) {
                throw PaymentException.bookingNotPayable(id);
            }
            setStatus("PAYMENT_PENDING");
            return facts;
        }
    }

    /** An HMAC gateway under the razorpay id that counts charges, can decline, and checks the booking moved first. */
    private final class CountingRazorpay extends AbstractHmacGatewayAdapter {
        int charges;
        boolean accept = true;

        CountingRazorpay() {
            super(RAZORPAY_SECRET);
        }

        @Override
        public String gatewayId() {
            return RazorpayGatewayAdapter.GATEWAY_ID;
        }

        @Override
        public GatewayChargeResult charge(GatewayChargeRequest request) {
            charges++;
            if (bookings != null) {
                bookings.chargedWhilePending.add("PAYMENT_PENDING".equals(bookings.facts.status()));
            }
            GatewayChargeResult result = super.charge(request);
            return new GatewayChargeResult(result.gatewayReference(), accept);
        }
    }
}
