package com.homefix.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;

import com.homefix.payment.alert.FinanceAlertPort;
import com.homefix.payment.config.PaymentProperties;
import com.homefix.payment.crypto.KmsEncryptionPort;
import com.homefix.payment.crypto.LocalAesKmsAdapter;
import com.homefix.payment.domain.PaymentMethod;
import com.homefix.payment.domain.PaymentRefund;
import com.homefix.payment.domain.PaymentTransaction;
import com.homefix.payment.domain.PaymentTransactionRepository;
import com.homefix.payment.domain.RefundStatus;
import com.homefix.payment.domain.Settlement;
import com.homefix.payment.domain.SettlementStatus;
import com.homefix.payment.domain.TransactionStatus;
import com.homefix.payment.gateway.GatewayChargeRequest;
import com.homefix.payment.gateway.GatewayChargeResult;
import com.homefix.payment.gateway.GatewayRefundRequest;
import com.homefix.payment.gateway.GatewayRefundResult;
import com.homefix.payment.gateway.GatewayTransferRequest;
import com.homefix.payment.gateway.GatewayTransferResult;
import com.homefix.payment.gateway.HmacSignatures;
import com.homefix.payment.gateway.PaymentGatewayPort;
import com.homefix.payment.gateway.PaymentGatewayRegistry;
import com.homefix.payment.gateway.RazorpayGatewayAdapter;
import com.homefix.payment.gateway.StripeGatewayAdapter;
import com.homefix.payment.idempotency.InMemoryIdempotencyStoreAdapter;
import com.homefix.payment.invoice.InvoiceTriggerException;
import com.homefix.payment.invoice.InvoiceTriggerPort;
import com.homefix.payment.notification.ProviderNotificationPort;
import com.homefix.payment.support.InMemoryPaymentRefundRepository;
import com.homefix.payment.support.InMemoryPaymentTransactionRepository;
import com.homefix.payment.support.InMemorySettlementRepository;
import com.homefix.payment.support.MarkingTransactionOperations;
import com.homefix.payment.support.RecordingPaymentCompletedPublisher;
import com.homefix.payment.wallet.ProviderWalletClientPort;
import com.homefix.payment.wallet.WalletCreditException;

/**
 * Unit tests for the payment domain logic (Requirement 12 and 14.3-14.4).
 *
 * <p>Tests operate against in-memory fakes, a recording outbox publisher, real HMAC gateway
 * adapters (the Razorpay one wrapped in a spy that records what it was asked and whether a
 * transaction was open), a real local KMS adapter, and {@link MarkingTransactionOperations}, which
 * marks the thread as transactional exactly where a real {@code TransactionTemplate} would. No
 * Spring context, database, network, or Redis is required. Properties 11/12/13 have a dedicated PBT
 * suite.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final String TEST_DATA_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String RAZORPAY_SECRET = "razorpay-test-secret";
    private static final String STRIPE_SECRET = "stripe-test-secret";
    private static final String RAZORPAY = RazorpayGatewayAdapter.GATEWAY_ID;
    private static final String STRIPE = StripeGatewayAdapter.GATEWAY_ID;

    private InMemoryPaymentTransactionRepository transactionRepository;
    private InMemoryPaymentRefundRepository refundRepository;
    private InMemorySettlementRepository settlementRepository;
    private InMemoryIdempotencyStoreAdapter idempotencyStore;
    private RecordingPaymentCompletedPublisher publisher;
    private KmsEncryptionPort kms;
    private SpyGateway razorpay;
    private PaymentGatewayRegistry gatewayRegistry;
    private PaymentProperties props;
    private MarkingTransactionOperations transactions;

    @Mock
    private ProviderWalletClientPort walletClient;
    @Mock
    private InvoiceTriggerPort invoiceTrigger;
    @Mock
    private FinanceAlertPort financeAlert;
    @Mock
    private ProviderNotificationPort providerNotification;

    private PaymentService service;

    @BeforeEach
    void setUp() {
        transactionRepository = new InMemoryPaymentTransactionRepository();
        refundRepository = new InMemoryPaymentRefundRepository();
        settlementRepository = new InMemorySettlementRepository();
        idempotencyStore = new InMemoryIdempotencyStoreAdapter();
        publisher = new RecordingPaymentCompletedPublisher();
        kms = new LocalAesKmsAdapter(TEST_DATA_KEY);
        transactions = new MarkingTransactionOperations();

        razorpay = new SpyGateway();
        PaymentGatewayPort stripe = new StripeGatewayAdapter(STRIPE_SECRET);
        gatewayRegistry = new PaymentGatewayRegistry(List.of(razorpay, stripe));

        props = new PaymentProperties();
        props.setRetryBackoff(Duration.ZERO); // keep tests fast — no real backoff sleeps

        service = newService(transactionRepository, gatewayRegistry);
    }

    private PaymentService newService(PaymentTransactionRepository repository, PaymentGatewayRegistry registry) {
        return new PaymentService(repository, refundRepository, settlementRepository, registry,
                idempotencyStore, kms, walletClient, invoiceTrigger, publisher, financeAlert,
                providerNotification, props, transactions);
    }

    private InitiatePaymentCommand paymentCmd(UUID customerId, UUID bookingId, UUID providerId,
                                              String amount, String platformFee) {
        return new InitiatePaymentCommand(customerId, bookingId, providerId,
                new BigDecimal(amount), platformFee == null ? null : new BigDecimal(platformFee),
                PaymentMethod.UPI, RAZORPAY, null);
    }

    private PaymentTransaction initiate(String amount, String platformFee) {
        return service.initiatePayment(paymentCmd(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), amount, platformFee));
    }

    // ----------------------------------------------------------------- signed callback helpers

    /** Builds a signed-payload JSON body following the callback contract. */
    private static String payload(UUID transactionId, String gatewayId, String status, String amount,
                                  String failureReason, Instant timestamp) {
        String reason = failureReason == null ? "" : "\"failureReason\":\"" + failureReason + "\",";
        return "{\"eventId\":\"evt_" + UUID.randomUUID() + "\","
                + "\"transactionId\":\"" + transactionId + "\","
                + "\"gatewayId\":\"" + gatewayId + "\","
                + "\"status\":\"" + status + "\","
                + "\"amount\":\"" + amount + "\","
                + reason
                + "\"timestamp\":\"" + timestamp + "\"}";
    }

    private static String successPayload(PaymentTransaction tx) {
        return payload(tx.getId(), RAZORPAY, "SUCCEEDED", tx.getAmount().toPlainString(), null, Instant.now());
    }

    private static String failurePayload(PaymentTransaction tx, String reason) {
        return payload(tx.getId(), RAZORPAY, "FAILED", tx.getAmount().toPlainString(), reason, Instant.now());
    }

    /** Wraps a payload in a callback carrying a valid Razorpay signature over it. */
    private static GatewayCallback razorpayCallback(String payload) {
        return new GatewayCallback(RAZORPAY, payload, HmacSignatures.hmacSha256Hex(RAZORPAY_SECRET, payload));
    }

    private PaymentTransaction succeed(PaymentTransaction tx) {
        return service.handleGatewayCallback(tx.getId(), razorpayCallback(successPayload(tx)));
    }

    private PaymentTransaction stored(PaymentTransaction tx) {
        return transactionRepository.findById(tx.getId()).orElseThrow();
    }

    private static void assertPaymentError(Throwable ex, HttpStatus status, String code) {
        assertThat(ex).isInstanceOf(PaymentException.class);
        PaymentException pe = (PaymentException) ex;
        assertThat(pe.getStatus()).isEqualTo(status);
        assertThat(pe.getErrorCode()).isEqualTo(code);
    }

    // ============================= Idempotency (Req 12.3, Property 11) =============================

    @Nested
    class Idempotency {

        @Test
        void duplicatePayment_returnsOriginalTransaction_withoutNewRecord() {
            UUID customerId = UUID.randomUUID();
            UUID bookingId = UUID.randomUUID();
            UUID providerId = UUID.randomUUID();

            PaymentTransaction first = service.initiatePayment(
                    paymentCmd(customerId, bookingId, providerId, "100.00", "20.00"));
            PaymentTransaction second = service.initiatePayment(
                    paymentCmd(customerId, bookingId, providerId, "100.00", "20.00"));

            // Same transaction id returned; only one record persisted; charged once.
            assertThat(second.getId()).isEqualTo(first.getId());
            assertThat(transactionRepository.findAll()).hasSize(1);
            assertThat(razorpay.charges).hasSize(1);
        }

        @Test
        void duplicateAfterSuccess_returnsOriginalStatus_withNoNewCharge() {
            UUID customerId = UUID.randomUUID();
            UUID bookingId = UUID.randomUUID();
            UUID providerId = UUID.randomUUID();

            PaymentTransaction tx = service.initiatePayment(
                    paymentCmd(customerId, bookingId, providerId, "100.00", "20.00"));
            succeed(tx);

            String originalRef = stored(tx).getGatewayReference();

            PaymentTransaction duplicate = service.initiatePayment(
                    paymentCmd(customerId, bookingId, providerId, "100.00", "20.00"));

            assertThat(duplicate.getId()).isEqualTo(tx.getId());
            assertThat(duplicate.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
            // No new charge: the gateway reference is unchanged.
            assertThat(duplicate.getGatewayReference()).isEqualTo(originalRef);
            assertThat(transactionRepository.findAll()).hasSize(1);
            assertThat(razorpay.charges).hasSize(1);
        }
    }

    // ============================= Charge after commit (roadmap 11.8) ==============================

    @Nested
    class ChargeAfterPendingCommits {

        @Test
        void pendingRowExistsBeforeCharge_andChargeRunsWithNoTransactionOpen() {
            List<Boolean> rowExistedAtCharge = new ArrayList<>();
            razorpay.onCharge = req -> rowExistedAtCharge.add(
                    transactionRepository.findById(req.transactionId()).isPresent());

            PaymentTransaction tx = initiate("100.00", "20.00");

            assertThat(rowExistedAtCharge).containsExactly(true);
            assertThat(razorpay.chargeInTransaction).containsExactly(false);
            // The charge carries our transaction id so the gateway can echo it in the signed callback.
            assertThat(razorpay.charges.get(0).transactionId()).isEqualTo(tx.getId());
            assertThat(tx.getGatewayReference()).startsWith("razorpay_ch_");
            assertThat(tx.getStatus()).isEqualTo(TransactionStatus.PENDING);
        }

        @Test
        void declinedCharge_marksTransactionFailed() {
            razorpay.chargeAccepted = false;

            PaymentTransaction tx = initiate("100.00", "20.00");

            assertThat(tx.getStatus()).isEqualTo(TransactionStatus.FAILED);
            assertThat(tx.getFailureReason()).isEqualTo("Gateway declined the charge");
        }

        @Test
        void chargeError_keepsPendingRecord_returns502_andDuplicateDoesNotRecharge() {
            razorpay.chargeError = new RuntimeException("gateway timeout");
            UUID customerId = UUID.randomUUID();
            UUID bookingId = UUID.randomUUID();
            UUID providerId = UUID.randomUUID();

            assertThatThrownBy(() -> service.initiatePayment(
                    paymentCmd(customerId, bookingId, providerId, "100.00", "20.00")))
                    .satisfies(ex -> assertPaymentError(ex, HttpStatus.BAD_GATEWAY, "PAYMENT_GATEWAY_ERROR"));

            // Outcome at the gateway is unknown, so the record stays PENDING for its callback.
            assertThat(transactionRepository.findAll()).singleElement().satisfies(tx -> {
                assertThat(tx.getStatus()).isEqualTo(TransactionStatus.PENDING);
                assertThat(tx.getFailureReason()).contains("gateway timeout");
            });

            razorpay.chargeError = null;
            PaymentTransaction duplicate = service.initiatePayment(
                    paymentCmd(customerId, bookingId, providerId, "100.00", "20.00"));
            assertThat(duplicate.getStatus()).isEqualTo(TransactionStatus.PENDING);
            assertThat(razorpay.charges).hasSize(1);
        }

        /**
         * Review finding: when the success callback committed between step 3's re-read and its write,
         * step 3 failed on the version check, the client got a 500 for a successful charge, and the
         * gateway reference (needed by every later refund) was never recorded.
         */
        @Test
        void callbackCommittingDuringStep3_isRetried_recordsReference_andKeepsSettledState() {
            InMemoryPaymentTransactionRepository racing = new InMemoryPaymentTransactionRepository() {
                private boolean conflicted;

                @Override
                public <S extends PaymentTransaction> S save(S entity) {
                    if (!conflicted && entity.getGatewayReference() != null) {
                        conflicted = true;
                        // Our write loses; the callback's SUCCESS commit wins.
                        entity.setGatewayReference(null);
                        entity.transitionTo(TransactionStatus.SUCCESS);
                        throw new OptimisticLockingFailureException("simulated concurrent callback");
                    }
                    return super.save(entity);
                }
            };
            PaymentService svc = newService(racing, gatewayRegistry);

            PaymentTransaction tx = svc.initiatePayment(paymentCmd(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "100.00", "20.00"));

            assertThat(tx.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
            assertThat(tx.getGatewayReference()).startsWith("razorpay_ch_");
            assertThat(razorpay.charges).hasSize(1);
        }

        @Test
        void step3ThatKeepsLosingTheRace_eventuallyPropagatesTheConflict() {
            InMemoryPaymentTransactionRepository alwaysConflicting = new InMemoryPaymentTransactionRepository() {
                @Override
                public <S extends PaymentTransaction> S save(S entity) {
                    if (entity.getGatewayReference() != null) {
                        throw new OptimisticLockingFailureException("simulated concurrent update");
                    }
                    return super.save(entity);
                }
            };
            PaymentService svc = newService(alwaysConflicting, gatewayRegistry);

            assertThatThrownBy(() -> svc.initiatePayment(paymentCmd(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "100.00", "20.00")))
                    .isInstanceOf(OptimisticLockingFailureException.class);
            assertThat(razorpay.charges).hasSize(1);
        }
    }

    // ============================= Money scale (numeric(12,2) columns) ============================

    @Nested
    class MoneyScale {

        @Test
        void paymentAmountWithMoreThanTwoDecimals_isRejectedBeforeAnythingIsStoredOrCharged() {
            assertThatThrownBy(() -> initiate("33.334", null))
                    .satisfies(ex -> assertPaymentError(ex, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR"));
            assertThat(transactionRepository.findAll()).isEmpty();
            assertThat(razorpay.charges).isEmpty();
        }

        @Test
        void platformFeeWithMoreThanTwoDecimals_isRejected() {
            assertThatThrownBy(() -> initiate("100.00", "1.005"))
                    .satisfies(ex -> assertPaymentError(ex, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR"));
            assertThat(razorpay.charges).isEmpty();
        }

        @Test
        void trailingZerosAreNotPrecision() {
            PaymentTransaction tx = initiate("33.3400", "1.000");

            assertThat(tx.getAmount()).isEqualByComparingTo("33.34");
            assertThat(razorpay.charges).hasSize(1);
        }

        @Test
        void settlementAmountWithMoreThanTwoDecimals_isRejected() {
            assertThatThrownBy(() -> service.initiateSettlement(UUID.randomUUID(),
                    new BigDecimal("10.001"), "ACCT-1", RAZORPAY))
                    .satisfies(ex -> assertPaymentError(ex, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR"));
            assertThat(settlementRepository.findAll()).isEmpty();
        }
    }

    // ============================= Callback signature and binding (Req 12.5) =====================

    @Nested
    class CallbackSignature {

        @Test
        void invalidSignature_isRejected_andStateUnchanged() {
            PaymentTransaction tx = initiate("100.00", "20.00");
            String payload = successPayload(tx);

            assertThatThrownBy(() -> service.handleGatewayCallback(tx.getId(),
                    new GatewayCallback(RAZORPAY, payload, "deadbeef-not-a-valid-sig")))
                    .satisfies(ex -> assertPaymentError(ex, HttpStatus.BAD_REQUEST, "INVALID_CALLBACK_SIGNATURE"));

            // State remains PENDING; no event published; no wallet credit.
            assertThat(stored(tx).getStatus()).isEqualTo(TransactionStatus.PENDING);
            assertThat(publisher.published()).isEmpty();
            verify(walletClient, never()).creditEarning(any(), any(), any(), any(), any());
        }

        @Test
        void validSignature_transitionsToSuccess_andRecordsEventId() {
            PaymentTransaction tx = initiate("100.00", "20.00");

            PaymentTransaction result = succeed(tx);

            assertThat(result.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
            assertThat(result.getCallbackEventId()).startsWith("evt_");
        }

        @Test
        void outcomeAndFailureReason_comeFromSignedPayload() {
            PaymentTransaction tx = initiate("100.00", "20.00");

            PaymentTransaction result = service.handleGatewayCallback(tx.getId(),
                    razorpayCallback(failurePayload(tx, "card declined")));

            assertThat(result.getStatus()).isEqualTo(TransactionStatus.FAILED);
            assertThat(result.getFailureReason()).isEqualTo("card declined");
            assertThat(publisher.published()).isEmpty();
        }

        /**
         * The critical finding in review 8.4: previously a signed payload was never bound to the
         * transaction, so one captured (gatewayId, payload, signature) triple marked ANY pending
         * transaction SUCCESS. The payload now names its transaction and is rejected elsewhere.
         */
        @Test
        void signedPayloadForAnotherTransaction_isRejected() {
            PaymentTransaction victim = initiate("100.00", "20.00");
            PaymentTransaction other = initiate("100.00", "20.00");
            GatewayCallback capturedForOther = razorpayCallback(successPayload(other));

            assertThatThrownBy(() -> service.handleGatewayCallback(victim.getId(), capturedForOther))
                    .satisfies(ex -> assertPaymentError(ex, HttpStatus.BAD_REQUEST, "CALLBACK_MISMATCH"));

            assertThat(stored(victim).getStatus()).isEqualTo(TransactionStatus.PENDING);
            assertThat(stored(other).getStatus()).isEqualTo(TransactionStatus.PENDING);
            assertThat(publisher.published()).isEmpty();
        }

        @Test
        void signedAmountDifferentFromTransaction_isRejected() {
            PaymentTransaction tx = initiate("100.00", "20.00");
            String payload = payload(tx.getId(), RAZORPAY, "SUCCEEDED", "1.00", null, Instant.now());

            assertThatThrownBy(() -> service.handleGatewayCallback(tx.getId(), razorpayCallback(payload)))
                    .satisfies(ex -> assertPaymentError(ex, HttpStatus.BAD_REQUEST, "CALLBACK_MISMATCH"));
            assertThat(stored(tx).getStatus()).isEqualTo(TransactionStatus.PENDING);
        }

        @Test
        void amountComparisonIgnoresScale() {
            PaymentTransaction tx = initiate("100.00", "20.00");
            String payload = payload(tx.getId(), RAZORPAY, "SUCCEEDED", "100", null, Instant.now());

            assertThat(service.handleGatewayCallback(tx.getId(), razorpayCallback(payload)).getStatus())
                    .isEqualTo(TransactionStatus.SUCCESS);
        }

        @Test
        void callbackSignedByAnotherGateway_isRejected() {
            PaymentTransaction razorpayTx = initiate("100.00", "20.00");
            // Validly signed by Stripe, naming Stripe, but the transaction belongs to Razorpay.
            String payload = payload(razorpayTx.getId(), STRIPE, "SUCCEEDED", "100.00", null, Instant.now());
            GatewayCallback stripeSigned = new GatewayCallback(STRIPE, payload,
                    HmacSignatures.hmacSha256Hex(STRIPE_SECRET, payload));

            assertThatThrownBy(() -> service.handleGatewayCallback(razorpayTx.getId(), stripeSigned))
                    .satisfies(ex -> assertPaymentError(ex, HttpStatus.BAD_REQUEST, "CALLBACK_MISMATCH"));
            assertThat(stored(razorpayTx).getStatus()).isEqualTo(TransactionStatus.PENDING);
        }

        @Test
        void payloadGatewayFieldMustMatchVerifyingGateway() {
            PaymentTransaction tx = initiate("100.00", "20.00");
            String payload = payload(tx.getId(), STRIPE, "SUCCEEDED", "100.00", null, Instant.now());

            assertThatThrownBy(() -> service.handleGatewayCallback(tx.getId(), razorpayCallback(payload)))
                    .satisfies(ex -> assertPaymentError(ex, HttpStatus.BAD_REQUEST, "CALLBACK_MISMATCH"));
        }

        /** Previously any validly signed bytes were accepted without being parsed. */
        @Test
        void validlySignedButUnparseablePayload_isRejected() {
            PaymentTransaction tx = initiate("100.00", "20.00");

            assertThatThrownBy(() -> service.handleGatewayCallback(tx.getId(),
                    razorpayCallback("ok:" + tx.getId())))
                    .satisfies(ex -> assertPaymentError(ex, HttpStatus.BAD_REQUEST, "INVALID_CALLBACK_PAYLOAD"));
            assertThat(stored(tx).getStatus()).isEqualTo(TransactionStatus.PENDING);
        }

        @Test
        void payloadMissingRequiredField_isRejected() {
            PaymentTransaction tx = initiate("100.00", "20.00");
            String noAmount = "{\"eventId\":\"evt_1\",\"transactionId\":\"" + tx.getId()
                    + "\",\"gatewayId\":\"razorpay\",\"status\":\"SUCCEEDED\",\"timestamp\":\""
                    + Instant.now() + "\"}";

            assertThatThrownBy(() -> service.handleGatewayCallback(tx.getId(), razorpayCallback(noAmount)))
                    .satisfies(ex -> assertPaymentError(ex, HttpStatus.BAD_REQUEST, "INVALID_CALLBACK_PAYLOAD"));
        }

        @Test
        void unknownStatusValue_isRejected() {
            PaymentTransaction tx = initiate("100.00", "20.00");
            String payload = payload(tx.getId(), RAZORPAY, "captured", "100.00", null, Instant.now());

            assertThatThrownBy(() -> service.handleGatewayCallback(tx.getId(), razorpayCallback(payload)))
                    .satisfies(ex -> assertPaymentError(ex, HttpStatus.BAD_REQUEST, "INVALID_CALLBACK_PAYLOAD"));
        }

        @Test
        void staleTimestamp_isRejected() {
            PaymentTransaction tx = initiate("100.00", "20.00");
            String payload = payload(tx.getId(), RAZORPAY, "SUCCEEDED", "100.00", null,
                    Instant.now().minus(Duration.ofHours(73)));

            assertThatThrownBy(() -> service.handleGatewayCallback(tx.getId(), razorpayCallback(payload)))
                    .satisfies(ex -> assertPaymentError(ex, HttpStatus.BAD_REQUEST, "CALLBACK_STALE"));
            assertThat(stored(tx).getStatus()).isEqualTo(TransactionStatus.PENDING);
        }

        @Test
        void futureTimestamp_isRejected() {
            PaymentTransaction tx = initiate("100.00", "20.00");
            String payload = payload(tx.getId(), RAZORPAY, "SUCCEEDED", "100.00", null,
                    Instant.now().plus(Duration.ofMinutes(10)));

            assertThatThrownBy(() -> service.handleGatewayCallback(tx.getId(), razorpayCallback(payload)))
                    .satisfies(ex -> assertPaymentError(ex, HttpStatus.BAD_REQUEST, "CALLBACK_STALE"));
        }

        @Test
        void zeroMaxAge_disablesAgeCheck() {
            props.setCallbackMaxAge(Duration.ZERO);
            PaymentTransaction tx = initiate("100.00", "20.00");
            String payload = payload(tx.getId(), RAZORPAY, "SUCCEEDED", "100.00", null,
                    Instant.parse("2020-01-01T00:00:00Z"));

            assertThat(service.handleGatewayCallback(tx.getId(), razorpayCallback(payload)).getStatus())
                    .isEqualTo(TransactionStatus.SUCCESS);
        }

        @Test
        void unknownTransaction_is404() {
            UUID unknown = UUID.randomUUID();
            String payload = payload(unknown, RAZORPAY, "SUCCEEDED", "100.00", null, Instant.now());

            assertThatThrownBy(() -> service.handleGatewayCallback(unknown, razorpayCallback(payload)))
                    .satisfies(ex -> assertPaymentError(ex, HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND"));
        }
    }

    // ============================= Callback replay protection (Req 12.4, 12.5) ====================

    @Nested
    class CallbackReplay {

        @Test
        void duplicateSuccessCallback_isIdempotentNoOp() {
            PaymentTransaction tx = initiate("100.00", "20.00");
            GatewayCallback callback = razorpayCallback(successPayload(tx));

            service.handleGatewayCallback(tx.getId(), callback);
            PaymentTransaction again = service.handleGatewayCallback(tx.getId(), callback);

            assertThat(again.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
            // Side effects happened exactly once.
            assertThat(publisher.published()).hasSize(1);
            verify(invoiceTrigger, times(1)).triggerInvoiceGeneration(any(), any());
            verify(walletClient, times(1)).creditEarning(any(), any(), any(), any(), any());
        }

        @Test
        void duplicateFailedCallback_isIdempotentNoOp() {
            PaymentTransaction tx = initiate("100.00", "20.00");
            GatewayCallback callback = razorpayCallback(failurePayload(tx, "declined"));

            service.handleGatewayCallback(tx.getId(), callback);
            assertThat(service.handleGatewayCallback(tx.getId(), callback).getStatus())
                    .isEqualTo(TransactionStatus.FAILED);
        }

        @Test
        void failedCallbackAfterSuccess_isRejectedAsConflict() {
            PaymentTransaction tx = initiate("100.00", "20.00");
            succeed(tx);

            assertThatThrownBy(() -> service.handleGatewayCallback(tx.getId(),
                    razorpayCallback(failurePayload(tx, "late failure"))))
                    .satisfies(ex -> assertPaymentError(ex, HttpStatus.CONFLICT, "CALLBACK_CONFLICT"));
            assertThat(stored(tx).getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        }

        @Test
        void successCallbackAfterFailure_isRejectedAsConflict() {
            PaymentTransaction tx = initiate("100.00", "20.00");
            service.handleGatewayCallback(tx.getId(), razorpayCallback(failurePayload(tx, "declined")));

            assertThatThrownBy(() -> succeed(tx))
                    .satisfies(ex -> assertPaymentError(ex, HttpStatus.CONFLICT, "CALLBACK_CONFLICT"));
            assertThat(stored(tx).getStatus()).isEqualTo(TransactionStatus.FAILED);
            assertThat(publisher.published()).isEmpty();
        }

        @Test
        void successCallbackAfterRefund_isNoOp() {
            PaymentTransaction tx = initiate("100.00", "20.00");
            succeed(tx);
            service.refund(tx.getId(), new BigDecimal("100.00"), "rf-1");

            assertThat(succeed(tx).getStatus()).isEqualTo(TransactionStatus.REFUNDED);
            assertThat(publisher.published()).hasSize(1);
        }

        @Test
        void callbackLosingAConcurrentUpdate_isReevaluatedInsteadOfFailing() {
            // Simulates a concurrent delivery committing first: the first save hits an optimistic
            // lock conflict, and the re-run sees the already-settled SUCCESS state.
            InMemoryPaymentTransactionRepository racing = new InMemoryPaymentTransactionRepository() {
                private boolean conflicted;

                @Override
                public <S extends PaymentTransaction> S save(S entity) {
                    if (!conflicted && entity.getStatus() == TransactionStatus.SUCCESS) {
                        conflicted = true;
                        throw new OptimisticLockingFailureException("simulated concurrent update");
                    }
                    return super.save(entity);
                }
            };
            PaymentService svc = newService(racing, gatewayRegistry);
            PaymentTransaction tx = svc.initiatePayment(paymentCmd(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "100.00", "20.00"));

            PaymentTransaction result = svc.handleGatewayCallback(tx.getId(), razorpayCallback(successPayload(tx)));

            assertThat(result.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
            // The winning delivery owns the side effects; the loser must not repeat them.
            verify(walletClient, never()).creditEarning(any(), any(), any(), any(), any());
        }
    }

    // ============================= Transaction boundaries (review 8.1, roadmap 11.8) ==============

    @Nested
    class TransactionBoundaries {

        @Test
        void postSuccessSideEffects_runAfterCommit_withNoTransactionOpen() {
            List<Boolean> invoiceInTx = new ArrayList<>();
            List<Boolean> walletInTx = new ArrayList<>();
            doAnswer(inv -> invoiceInTx.add(MarkingTransactionOperations.inTransaction()))
                    .when(invoiceTrigger).triggerInvoiceGeneration(any(), any());
            doAnswer(inv -> walletInTx.add(MarkingTransactionOperations.inTransaction()))
                    .when(walletClient).creditEarning(any(), any(), any(), any(), any());
            PaymentTransaction tx = initiate("100.00", "20.00");

            succeed(tx);

            assertThat(invoiceInTx).containsExactly(false);
            assertThat(walletInTx).containsExactly(false);
            // The outbox event was written inside the state-change transaction (the recording
            // publisher refuses to publish outside one, mirroring Propagation.MANDATORY).
            assertThat(publisher.published()).hasSize(1);
        }

        @Test
        void retries_refuseToRunInsideATransaction() {
            assertThatThrownBy(() -> transactions.executeWithoutResult(
                    status -> Retries.run(3, Duration.ofSeconds(1), () -> { })))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must not run inside a database transaction");
        }

        @Test
        void refundGatewayCall_runsWithNoTransactionOpen() {
            PaymentTransaction tx = initiate("100.00", "20.00");
            succeed(tx);

            service.refund(tx.getId(), new BigDecimal("10.00"), "rf-1");

            assertThat(razorpay.refundInTransaction).containsExactly(false);
        }

        @Test
        void settlementTransfer_runsWithNoTransactionOpen() {
            service.initiateSettlement(UUID.randomUUID(), new BigDecimal("500.00"), "ACCT-1", RAZORPAY);

            assertThat(razorpay.transferInTransaction).containsExactly(false);
        }
    }

    // ============================= Wallet credit on success (Req 12.10, Property 13) ==============

    @Nested
    class WalletCreditOnSuccess {

        @Test
        void success_creditsProviderNetEarning() {
            UUID providerId = UUID.randomUUID();
            PaymentTransaction tx = service.initiatePayment(paymentCmd(
                    UUID.randomUUID(), UUID.randomUUID(), providerId, "100.00", "15.00"));

            succeed(tx);

            // Net = 100 - 15 = 85; credited exactly once.
            verify(walletClient, times(1)).creditEarning(
                    eq(providerId), eq(tx.getBookingId()),
                    eq(new BigDecimal("100.00")), eq(new BigDecimal("15.00")),
                    eq(new BigDecimal("85.00")));
        }

        @Test
        void success_derivesPlatformFeeFromPercentWhenNotProvided() {
            UUID providerId = UUID.randomUUID();
            // No explicit fee -> default 20% of 200.00 = 40.00; net = 160.00.
            PaymentTransaction tx = service.initiatePayment(paymentCmd(
                    UUID.randomUUID(), UUID.randomUUID(), providerId, "200.00", null));

            assertThat(tx.getPlatformFee()).isEqualByComparingTo("40.00");

            succeed(tx);

            verify(walletClient).creditEarning(eq(providerId), eq(tx.getBookingId()),
                    eq(new BigDecimal("200.00")), eq(new BigDecimal("40.00")),
                    eq(new BigDecimal("160.00")));
        }

        @Test
        void success_publishesPaymentCompletedAndTriggersInvoice() {
            PaymentTransaction tx = initiate("50.00", "5.00");

            succeed(tx);

            assertThat(publisher.published()).extracting(PaymentTransaction::getId).containsExactly(tx.getId());
            verify(invoiceTrigger).triggerInvoiceGeneration(tx.getId(), tx.getBookingId());
        }

        @Test
        void walletCreditFailure_retriesThenAlertsFinanceAdmin() {
            props.setMaxWalletCreditRetries(3);
            UUID providerId = UUID.randomUUID();
            PaymentTransaction tx = service.initiatePayment(paymentCmd(
                    UUID.randomUUID(), UUID.randomUUID(), providerId, "100.00", "10.00"));

            doThrow(new WalletCreditException("wallet down"))
                    .when(walletClient).creditEarning(any(), any(), any(), any(), any());

            succeed(tx);

            // 3 attempts made, then Finance_Admin alerted.
            verify(walletClient, times(3)).creditEarning(any(), any(), any(), any(), any());
            verify(financeAlert).walletCreditFailed(eq(providerId), eq(tx.getBookingId()),
                    eq(new BigDecimal("90.00")), any());
        }

        /**
         * Review finding: the credit runs after the SUCCESS commit, so a crash in between lost it.
         * The debt is now committed with SUCCESS and only cleared once the wallet accepted it.
         */
        @Test
        void creditIsOwedDurablyFromTheSuccessCommit_untilTheWalletAcceptsIt() {
            List<Boolean> owedWhenCredited = new ArrayList<>();
            PaymentTransaction tx = initiate("100.00", "10.00");
            assertThat(stored(tx).isWalletCreditPending()).isFalse();
            doAnswer(inv -> owedWhenCredited.add(stored(tx).isWalletCreditPending()))
                    .when(walletClient).creditEarning(any(), any(), any(), any(), any());

            succeed(tx);

            assertThat(owedWhenCredited).containsExactly(true);
            assertThat(stored(tx).isWalletCreditPending()).isFalse();
        }

        @Test
        void creditStillOwedAfterInlineRetries_isDeliveredByTheSweep() {
            props.setMaxWalletCreditRetries(3);
            props.setWalletCreditSweepMinAge(Duration.ZERO);
            UUID providerId = UUID.randomUUID();
            PaymentTransaction tx = service.initiatePayment(paymentCmd(
                    UUID.randomUUID(), UUID.randomUUID(), providerId, "100.00", "10.00"));
            doThrow(new WalletCreditException("wallet down"))
                    .when(walletClient).creditEarning(any(), any(), any(), any(), any());
            succeed(tx);
            assertThat(stored(tx).isWalletCreditPending()).isTrue();

            doNothing().when(walletClient).creditEarning(any(), any(), any(), any(), any());
            assertThat(service.retryPendingWalletCredits()).isEqualTo(1);

            assertThat(stored(tx).isWalletCreditPending()).isFalse();
            verify(walletClient, times(4)).creditEarning(eq(providerId), eq(tx.getBookingId()),
                    eq(new BigDecimal("100.00")), eq(new BigDecimal("10.00")), eq(new BigDecimal("90.00")));
            // Nothing left to sweep.
            assertThat(service.retryPendingWalletCredits()).isZero();
        }

        @Test
        void sweep_leavesCreditsYoungerThanTheMinimumAgeToTheInlineRetries() {
            props.setWalletCreditSweepMinAge(Duration.ofMinutes(5));
            doThrow(new WalletCreditException("wallet down"))
                    .when(walletClient).creditEarning(any(), any(), any(), any(), any());
            PaymentTransaction tx = succeed(initiate("100.00", "10.00"));

            assertThat(service.retryPendingWalletCredits()).isZero();
            verify(walletClient, times(props.getMaxWalletCreditRetries()))
                    .creditEarning(any(), any(), any(), any(), any());
            assertThat(stored(tx).isWalletCreditPending()).isTrue();
        }

        @Test
        void sweep_keepsACreditThatFailsAgainOwed_andStillDeliversTheOthers() {
            props.setWalletCreditSweepMinAge(Duration.ZERO);
            UUID unluckyProvider = UUID.randomUUID();
            PaymentTransaction unlucky = service.initiatePayment(paymentCmd(
                    UUID.randomUUID(), UUID.randomUUID(), unluckyProvider, "100.00", "10.00"));
            PaymentTransaction lucky = initiate("50.00", "5.00");
            doThrow(new WalletCreditException("wallet down"))
                    .when(walletClient).creditEarning(any(), any(), any(), any(), any());
            succeed(unlucky);
            succeed(lucky);

            doNothing().when(walletClient).creditEarning(any(), any(), any(), any(), any());
            doThrow(new WalletCreditException("provider wallet locked"))
                    .when(walletClient).creditEarning(eq(unluckyProvider), any(), any(), any(), any());

            assertThat(service.retryPendingWalletCredits()).isEqualTo(1);
            assertThat(stored(unlucky).isWalletCreditPending()).isTrue();
            assertThat(stored(lucky).isWalletCreditPending()).isFalse();
        }

        @Test
        void failingToClearTheMarker_doesNotFailTheCallback() {
            InMemoryPaymentTransactionRepository flaky = new InMemoryPaymentTransactionRepository() {
                @Override
                public int clearWalletCreditPending(UUID id) {
                    throw new org.springframework.dao.QueryTimeoutException("simulated DB blip");
                }
            };
            PaymentService svc = newService(flaky, gatewayRegistry);
            PaymentTransaction tx = svc.initiatePayment(paymentCmd(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "100.00", "10.00"));

            PaymentTransaction result = svc.handleGatewayCallback(tx.getId(), razorpayCallback(successPayload(tx)));

            assertThat(result.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
            verify(walletClient).creditEarning(any(), any(), any(), any(), any());
            // Still marked owed: the sweep re-sends it, and the wallet de-duplicates per booking.
            assertThat(result.isWalletCreditPending()).isTrue();
        }

        @Test
        void invoiceTriggerFailure_retriesUpToMax() {
            props.setMaxInvoiceRetries(3);
            PaymentTransaction tx = initiate("100.00", "10.00");

            doThrow(new InvoiceTriggerException("invoice down"))
                    .when(invoiceTrigger).triggerInvoiceGeneration(any(), any());

            // Does not throw; the payment still succeeds even if invoice trigger ultimately fails.
            succeed(tx);

            verify(invoiceTrigger, times(3)).triggerInvoiceGeneration(any(), any());
            assertThat(stored(tx).getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        }
    }

    // ============================= State machine (Req 12.4, Property 12) ==========================

    @Nested
    class StateMachine {

        @Test
        void failedIsTerminal_cannotTransition() {
            PaymentTransaction tx = initiate("100.00", "10.00");
            service.handleGatewayCallback(tx.getId(), razorpayCallback(failurePayload(tx, "card declined")));

            PaymentTransaction failed = stored(tx);
            assertThat(failed.getStatus()).isEqualTo(TransactionStatus.FAILED);
            assertThat(failed.getStatus().isTerminal()).isTrue();
            // FAILED -> anything is rejected by the entity guard.
            assertThatThrownBy(() -> failed.transitionTo(TransactionStatus.SUCCESS))
                    .isInstanceOf(PaymentException.class)
                    .satisfies(ex -> assertThat(((PaymentException) ex).getStatus())
                            .isEqualTo(HttpStatus.CONFLICT));
        }

        @Test
        void pendingCannotJumpToRefunded() {
            PaymentTransaction tx = initiate("100.00", "10.00");
            assertThatThrownBy(() -> tx.transitionTo(TransactionStatus.REFUNDED))
                    .isInstanceOf(PaymentException.class)
                    .satisfies(ex -> assertThat(((PaymentException) ex).getErrorCode())
                            .isEqualTo("INVALID_STATE_TRANSITION"));
        }

        @Test
        void refundOnPendingTransaction_isRejected() {
            PaymentTransaction tx = initiate("100.00", "10.00");
            assertThatThrownBy(() -> service.refund(tx.getId(), new BigDecimal("10.00"), "rf-1"))
                    .isInstanceOf(PaymentException.class)
                    .satisfies(ex -> assertThat(((PaymentException) ex).getStatus())
                            .isEqualTo(HttpStatus.CONFLICT));
            assertThat(razorpay.refunds).isEmpty();
        }

        @Test
        void permittedTargetsMatchSpec() {
            assertThat(TransactionStatus.PENDING.permittedTargets())
                    .containsExactlyInAnyOrder(TransactionStatus.SUCCESS, TransactionStatus.FAILED);
            assertThat(TransactionStatus.SUCCESS.permittedTargets())
                    .containsExactlyInAnyOrder(TransactionStatus.REFUNDED,
                            TransactionStatus.PARTIALLY_REFUNDED);
            assertThat(TransactionStatus.FAILED.isTerminal()).isTrue();
            assertThat(TransactionStatus.REFUNDED.isTerminal()).isTrue();
        }
    }

    // ============================= Customer retry (Req 12.8) =====================================

    @Nested
    class CustomerRetry {

        @Test
        void reachingMaxAttempts_marksPermanentlyFailed() {
            props.setMaxCustomerRetries(3);
            PaymentTransaction tx = initiate("100.00", "10.00"); // attemptCount starts at 1

            service.retryPayment(tx.getId(), "declined"); // attempt 2
            PaymentTransaction afterThird = service.retryPayment(tx.getId(), "declined"); // attempt 3

            assertThat(afterThird.getAttemptCount()).isEqualTo(3);
            assertThat(afterThird.getStatus()).isEqualTo(TransactionStatus.FAILED);
        }

        @Test
        void beforeMaxAttempts_remainsPending() {
            props.setMaxCustomerRetries(3);
            PaymentTransaction tx = initiate("100.00", "10.00"); // attempt 1
            PaymentTransaction afterSecond = service.retryPayment(tx.getId(), "declined"); // attempt 2
            assertThat(afterSecond.getStatus()).isEqualTo(TransactionStatus.PENDING);
        }
    }

    // ============================= Refund flow (Req 12.7) ========================================

    @Nested
    class RefundFlow {

        private PaymentTransaction succeeded(String amount, String fee) {
            return succeed(initiate(amount, fee));
        }

        @Test
        void fullRefund_movesToRefunded() {
            PaymentTransaction tx = succeeded("100.00", "10.00");
            PaymentTransaction refunded = service.refund(tx.getId(), new BigDecimal("100.00"), "rf-1");
            assertThat(refunded.getStatus()).isEqualTo(TransactionStatus.REFUNDED);
            assertThat(refunded.getRefundedAmount()).isEqualByComparingTo("100.00");
            assertThat(refundRepository.findAll()).singleElement().satisfies(r -> {
                assertThat(r.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
                assertThat(r.getGatewayReference()).startsWith("razorpay_rf_");
            });
        }

        @Test
        void partialRefund_movesToPartiallyRefunded() {
            PaymentTransaction tx = succeeded("100.00", "10.00");
            PaymentTransaction refunded = service.refund(tx.getId(), new BigDecimal("40.00"), "rf-1");
            assertThat(refunded.getStatus()).isEqualTo(TransactionStatus.PARTIALLY_REFUNDED);
            assertThat(refunded.getRefundedAmount()).isEqualByComparingTo("40.00");
        }

        @Test
        void gatewayReceivesRefundIdAsIdempotencyKey() {
            PaymentTransaction tx = succeeded("100.00", "10.00");
            service.refund(tx.getId(), new BigDecimal("40.00"), "rf-1");

            PaymentRefund refund = refundRepository.findAll().get(0);
            assertThat(razorpay.refunds).singleElement().satisfies(req -> {
                assertThat(req.idempotencyKey()).isEqualTo(refund.getId().toString());
                assertThat(req.gatewayChargeReference()).isEqualTo(tx.getGatewayReference());
            });
        }

        /** Review 8.4: previously the gateway refunded first and the amount check then rolled back. */
        @Test
        void overLimitRefund_isRejectedBeforeTheGatewayIsCalled() {
            PaymentTransaction tx = succeeded("100.00", "10.00");

            assertThatThrownBy(() -> service.refund(tx.getId(), new BigDecimal("150.00"), "rf-1"))
                    .satisfies(ex -> assertPaymentError(ex, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR"));

            assertThat(razorpay.refunds).isEmpty();
            assertThat(refundRepository.findAll()).isEmpty();
            assertThat(stored(tx).getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        }

        /**
         * PARTIALLY_REFUNDED is terminal in the state machine, so a second refund cannot be
         * recorded. Previously the gateway still refunded before that was discovered.
         */
        @Test
        void refundOfPartiallyRefundedTransaction_isRejectedBeforeTheGatewayIsCalled() {
            PaymentTransaction tx = succeeded("100.00", "10.00");
            service.refund(tx.getId(), new BigDecimal("40.00"), "rf-1");

            assertThatThrownBy(() -> service.refund(tx.getId(), new BigDecimal("10.00"), "rf-2"))
                    .satisfies(ex -> assertPaymentError(ex, HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION"));

            assertThat(razorpay.refunds).hasSize(1);
            assertThat(stored(tx).getRefundedAmount()).isEqualByComparingTo("40.00");
        }

        @Test
        void retriedRefundWithSameKey_refundsOnlyOnce() {
            PaymentTransaction tx = succeeded("100.00", "10.00");

            PaymentTransaction first = service.refund(tx.getId(), new BigDecimal("40.00"), "rf-1");
            PaymentTransaction retry = service.refund(tx.getId(), new BigDecimal("40.00"), "rf-1");

            assertThat(retry.getId()).isEqualTo(first.getId());
            assertThat(retry.getStatus()).isEqualTo(TransactionStatus.PARTIALLY_REFUNDED);
            assertThat(retry.getRefundedAmount()).isEqualByComparingTo("40.00");
            assertThat(razorpay.refunds).hasSize(1);
            assertThat(refundRepository.findAll()).hasSize(1);
        }

        @Test
        void sameKeyWithDifferentAmount_isRejected() {
            PaymentTransaction tx = succeeded("100.00", "10.00");
            service.refund(tx.getId(), new BigDecimal("40.00"), "rf-1");

            assertThatThrownBy(() -> service.refund(tx.getId(), new BigDecimal("50.00"), "rf-1"))
                    .satisfies(ex -> assertPaymentError(ex, HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED"));
            assertThat(razorpay.refunds).hasSize(1);
        }

        @Test
        void refundInProgress_blocksARefundWithAnotherKey() {
            PaymentTransaction tx = succeeded("100.00", "10.00");
            // A refund whose gateway call is still running (or whose outcome was lost).
            refundRepository.save(PaymentRefund.reserve(tx.getId(),
                    IdempotencyKeys.forRefund(tx.getId(), "rf-inflight"), new BigDecimal("40.00")));

            assertThatThrownBy(() -> service.refund(tx.getId(), new BigDecimal("40.00"), "rf-2"))
                    .satisfies(ex -> assertPaymentError(ex, HttpStatus.CONFLICT, "REFUND_IN_PROGRESS"));
            assertThat(razorpay.refunds).isEmpty();
        }

        /** A same-key retry of a PENDING refund re-sends it under its own id, which unblocks the transaction. */
        @Test
        void sameKeyRetryOfPendingRefund_resendsUnderTheSameRefundId() {
            PaymentTransaction tx = succeeded("100.00", "10.00");
            PaymentRefund inflight = refundRepository.save(PaymentRefund.reserve(tx.getId(),
                    IdempotencyKeys.forRefund(tx.getId(), "rf-inflight"), new BigDecimal("40.00")));

            PaymentTransaction result = service.refund(tx.getId(), new BigDecimal("40.00"), "rf-inflight");

            assertThat(result.getStatus()).isEqualTo(TransactionStatus.PARTIALLY_REFUNDED);
            assertThat(razorpay.refunds).singleElement()
                    .satisfies(req -> assertThat(req.idempotencyKey()).isEqualTo(inflight.getId().toString()));
            assertThat(refundRepository.findAll()).singleElement()
                    .satisfies(r -> assertThat(r.getStatus()).isEqualTo(RefundStatus.SUCCEEDED));
        }

        @Test
        void missingIdempotencyKey_isRejected() {
            PaymentTransaction tx = succeeded("100.00", "10.00");

            assertThatThrownBy(() -> service.refund(tx.getId(), new BigDecimal("40.00"), " "))
                    .satisfies(ex -> assertPaymentError(ex, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR"));
            assertThat(razorpay.refunds).isEmpty();
        }

        @Test
        void gatewayRefundFailure_alertsFinanceAdmin_andDoesNotChangeState() {
            PaymentTransaction tx = succeeded("100.00", "10.00");
            razorpay.refundSucceeds = false;

            assertThatThrownBy(() -> service.refund(tx.getId(), new BigDecimal("100.00"), "rf-1"))
                    .satisfies(ex -> assertPaymentError(ex, HttpStatus.BAD_GATEWAY, "REFUND_GATEWAY_ERROR"));

            verify(financeAlert).refundFailed(eq(tx.getId()), eq(tx.getBookingId()),
                    eq(new BigDecimal("100.00")), any());
            // State remains SUCCESS since the refund did not complete; the attempt is recorded.
            assertThat(stored(tx).getStatus()).isEqualTo(TransactionStatus.SUCCESS);
            assertThat(refundRepository.findAll()).singleElement()
                    .satisfies(r -> assertThat(r.getStatus()).isEqualTo(RefundStatus.FAILED));
        }

        @Test
        void failedRefund_replayDoesNotCallGatewayAgain_butANewKeyDoes() {
            PaymentTransaction tx = succeeded("100.00", "10.00");
            razorpay.refundSucceeds = false;
            assertThatThrownBy(() -> service.refund(tx.getId(), new BigDecimal("30.00"), "rf-1"))
                    .isInstanceOf(PaymentException.class);

            razorpay.refundSucceeds = true;
            assertThatThrownBy(() -> service.refund(tx.getId(), new BigDecimal("30.00"), "rf-1"))
                    .satisfies(ex -> assertPaymentError(ex, HttpStatus.BAD_GATEWAY, "REFUND_GATEWAY_ERROR"));
            assertThat(razorpay.refunds).hasSize(1);

            PaymentTransaction refunded = service.refund(tx.getId(), new BigDecimal("30.00"), "rf-2");
            assertThat(refunded.getStatus()).isEqualTo(TransactionStatus.PARTIALLY_REFUNDED);
            assertThat(razorpay.refunds).hasSize(2);
        }

        /**
         * A gateway call that throws has an unknown outcome: the refund may have executed. Marking it
         * FAILED told the client to retry under a new key, i.e. a new refund id the gateway could not
         * de-duplicate, so the customer could be refunded twice.
         */
        @Test
        void gatewayRefundThrowing_leavesRefundPending_alerts_andReportsOutcomeUnknown() {
            PaymentTransaction tx = succeeded("100.00", "10.00");
            razorpay.refundError = new RuntimeException("connection reset");

            assertThatThrownBy(() -> service.refund(tx.getId(), new BigDecimal("10.00"), "rf-1"))
                    .satisfies(ex -> assertPaymentError(ex, HttpStatus.BAD_GATEWAY, "REFUND_OUTCOME_UNKNOWN"))
                    .hasMessageContaining("same idempotency key")
                    .hasMessageNotContaining("new idempotency key");

            assertThat(refundRepository.findAll()).singleElement().satisfies(r -> {
                assertThat(r.getStatus()).isEqualTo(RefundStatus.PENDING);
                assertThat(r.getFailureReason()).contains("connection reset");
            });
            verify(financeAlert).refundFailed(eq(tx.getId()), eq(tx.getBookingId()),
                    eq(new BigDecimal("10.00")), any());
            assertThat(stored(tx).getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        }

        @Test
        void sameKeyRetryAfterUnknownOutcome_resendsUnderTheSameRefundId_andRecordsTheResult() {
            PaymentTransaction tx = succeeded("100.00", "10.00");
            razorpay.refundError = new RuntimeException("read timed out");
            assertThatThrownBy(() -> service.refund(tx.getId(), new BigDecimal("30.00"), "rf-1"))
                    .isInstanceOf(PaymentException.class);

            razorpay.refundError = null;
            PaymentTransaction refunded = service.refund(tx.getId(), new BigDecimal("30.00"), "rf-1");

            assertThat(refunded.getStatus()).isEqualTo(TransactionStatus.PARTIALLY_REFUNDED);
            assertThat(refunded.getRefundedAmount()).isEqualByComparingTo("30.00");
            // Both gateway calls carried the same gateway idempotency key, so the gateway can
            // de-duplicate them if the first one had in fact executed.
            assertThat(razorpay.refunds).hasSize(2)
                    .extracting(GatewayRefundRequest::idempotencyKey)
                    .containsOnly(refundRepository.findAll().get(0).getId().toString());
            assertThat(refundRepository.findAll()).singleElement()
                    .satisfies(r -> assertThat(r.getStatus()).isEqualTo(RefundStatus.SUCCEEDED));
        }

        @Test
        void unknownOutcome_blocksARefundWithANewKey() {
            PaymentTransaction tx = succeeded("100.00", "10.00");
            razorpay.refundError = new RuntimeException("connection reset");
            assertThatThrownBy(() -> service.refund(tx.getId(), new BigDecimal("30.00"), "rf-1"))
                    .isInstanceOf(PaymentException.class);
            razorpay.refundError = null;

            assertThatThrownBy(() -> service.refund(tx.getId(), new BigDecimal("30.00"), "rf-2"))
                    .satisfies(ex -> assertPaymentError(ex, HttpStatus.CONFLICT, "REFUND_IN_PROGRESS"));
            assertThat(razorpay.refunds).hasSize(1);
        }

        @Test
        void resendWhoseRefundWasRecordedConcurrently_isNotAppliedTwice() {
            PaymentTransaction tx = succeeded("100.00", "10.00");
            PaymentRefund inflight = refundRepository.save(PaymentRefund.reserve(tx.getId(),
                    IdempotencyKeys.forRefund(tx.getId(), "rf-1"), new BigDecimal("40.00")));
            // While this re-send is at the gateway, the original request records the same refund.
            razorpay.onRefund = req -> {
                stored(tx).applyRefund(new BigDecimal("40.00"));
                inflight.markSucceeded("razorpay_rf_original");
            };

            PaymentTransaction result = service.refund(tx.getId(), new BigDecimal("40.00"), "rf-1");

            assertThat(result.getRefundedAmount()).isEqualByComparingTo("40.00");
            assertThat(inflight.getGatewayReference()).isEqualTo("razorpay_rf_original");
        }

        /** Review finding: a fast callback could leave the gateway reference unrecorded. */
        @Test
        void transactionWithoutGatewayReference_isNotSentToTheGateway() {
            PaymentTransaction tx = succeeded("100.00", "10.00");
            stored(tx).setGatewayReference(null);

            assertThatThrownBy(() -> service.refund(tx.getId(), new BigDecimal("10.00"), "rf-1"))
                    .satisfies(ex -> assertPaymentError(ex, HttpStatus.CONFLICT, "GATEWAY_REFERENCE_MISSING"));
            assertThat(razorpay.refunds).isEmpty();
            assertThat(refundRepository.findAll()).isEmpty();
        }

        @Test
        void refundAmountWithMoreThanTwoDecimals_isRejectedBeforeTheGatewayIsCalled() {
            PaymentTransaction tx = succeeded("100.00", "10.00");

            assertThatThrownBy(() -> service.refund(tx.getId(), new BigDecimal("33.334"), "rf-1"))
                    .satisfies(ex -> assertPaymentError(ex, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR"));
            assertThat(razorpay.refunds).isEmpty();
            assertThat(refundRepository.findAll()).isEmpty();
        }
    }

    // ============================= Refund reconciliation (Req 12.7) ==============================

    @Nested
    class RefundReconciliation {

        private PaymentTransaction tx;

        @BeforeEach
        void succeededPayment() {
            tx = succeed(initiate("100.00", "10.00"));
        }

        private PaymentRefund pendingRefund(String clientKey, String amount) {
            return refundRepository.save(PaymentRefund.reserve(tx.getId(),
                    IdempotencyKeys.forRefund(tx.getId(), clientKey), new BigDecimal(amount)));
        }

        @Test
        void pendingRefund_isResentUnderItsOwnId_andRecorded() {
            PaymentRefund stuck = pendingRefund("rf-1", "25.00");

            PaymentTransaction result = service.reconcileRefund(tx.getId(), stuck.getId());

            assertThat(result.getStatus()).isEqualTo(TransactionStatus.PARTIALLY_REFUNDED);
            assertThat(result.getRefundedAmount()).isEqualByComparingTo("25.00");
            assertThat(razorpay.refunds).singleElement().satisfies(req -> {
                assertThat(req.idempotencyKey()).isEqualTo(stuck.getId().toString());
                assertThat(req.amount()).isEqualByComparingTo("25.00");
            });
            assertThat(stuck.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
            assertThat(razorpay.refundInTransaction).containsExactly(false);
        }

        @Test
        void reconcilingUnblocksFurtherRefunds() {
            PaymentRefund stuck = pendingRefund("rf-1", "100.00");
            service.reconcileRefund(tx.getId(), stuck.getId());

            assertThat(stored(tx).getStatus()).isEqualTo(TransactionStatus.REFUNDED);
            assertThat(refundRepository.existsByTransactionIdAndStatus(tx.getId(), RefundStatus.PENDING))
                    .isFalse();
        }

        @Test
        void gatewayStillUnreachable_keepsRefundPending() {
            PaymentRefund stuck = pendingRefund("rf-1", "25.00");
            razorpay.refundError = new RuntimeException("connection reset");

            assertThatThrownBy(() -> service.reconcileRefund(tx.getId(), stuck.getId()))
                    .satisfies(ex -> assertPaymentError(ex, HttpStatus.BAD_GATEWAY, "REFUND_OUTCOME_UNKNOWN"));
            assertThat(stuck.getStatus()).isEqualTo(RefundStatus.PENDING);
        }

        @Test
        void succeededRefund_isANoOp() {
            service.refund(tx.getId(), new BigDecimal("25.00"), "rf-1");
            PaymentRefund done = refundRepository.findAll().get(0);

            PaymentTransaction result = service.reconcileRefund(tx.getId(), done.getId());

            assertThat(result.getRefundedAmount()).isEqualByComparingTo("25.00");
            assertThat(razorpay.refunds).hasSize(1);
        }

        @Test
        void rejectedRefund_cannotBeReconciled() {
            razorpay.refundSucceeds = false;
            assertThatThrownBy(() -> service.refund(tx.getId(), new BigDecimal("25.00"), "rf-1"))
                    .isInstanceOf(PaymentException.class);
            PaymentRefund rejected = refundRepository.findAll().get(0);

            assertThatThrownBy(() -> service.reconcileRefund(tx.getId(), rejected.getId()))
                    .satisfies(ex -> assertPaymentError(ex, HttpStatus.CONFLICT, "REFUND_NOT_PENDING"));
            assertThat(razorpay.refunds).hasSize(1);
        }

        @Test
        void unknownRefund_orRefundOfAnotherTransaction_is404() {
            PaymentRefund stuck = pendingRefund("rf-1", "25.00");
            PaymentTransaction other = succeed(initiate("50.00", "5.00"));

            assertThatThrownBy(() -> service.reconcileRefund(tx.getId(), UUID.randomUUID()))
                    .satisfies(ex -> assertPaymentError(ex, HttpStatus.NOT_FOUND, "REFUND_NOT_FOUND"));
            assertThatThrownBy(() -> service.reconcileRefund(other.getId(), stuck.getId()))
                    .satisfies(ex -> assertPaymentError(ex, HttpStatus.NOT_FOUND, "REFUND_NOT_FOUND"));
            assertThat(razorpay.refunds).isEmpty();
        }
    }

    // ============================= Settlement (Req 14.3, 14.4) ===================================

    @Nested
    class SettlementFlow {

        @Test
        void successfulTransfer_completesSettlement_andEncryptsBankRef() {
            UUID providerId = UUID.randomUUID();
            Settlement settlement = service.initiateSettlement(providerId, new BigDecimal("500.00"),
                    "ACCT-999", RAZORPAY);

            assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.COMPLETED);
            assertThat(settlement.getBankAccountRefEncrypted())
                    .isNotEqualTo("ACCT-999")
                    .startsWith("v1:");
            assertThat(kms.decrypt(settlement.getBankAccountRefEncrypted())).isEqualTo("ACCT-999");
        }

        @Test
        void failedTransfer_marksFailed_creditsBack_notifiesProviderAndFinance() {
            UUID providerId = UUID.randomUUID();
            razorpay.transferSucceeds = false;

            Settlement settlement = service.initiateSettlement(providerId, new BigDecimal("500.00"),
                    "ACCT-999", RAZORPAY);

            assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.FAILED);
            verify(walletClient).creditSettlementReversal(eq(providerId), eq(settlement.getId()),
                    eq(new BigDecimal("500.00")));
            verify(providerNotification).settlementFailed(eq(providerId), eq(settlement.getId()),
                    eq(new BigDecimal("500.00")));
            verify(financeAlert).settlementFailed(eq(providerId), eq(settlement.getId()),
                    eq(new BigDecimal("500.00")), any());
        }
    }

    // ============================= Gateway abstraction (Req 12.1) =================================

    @Nested
    class GatewayAbstraction {

        @Test
        void bothGatewaysAreRegistered_andSelectableById() {
            assertThat(gatewayRegistry.registeredGateways()).contains(RAZORPAY, STRIPE);
        }

        @Test
        void unknownGateway_isRejected() {
            assertThatThrownBy(() -> service.initiatePayment(new InitiatePaymentCommand(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    new BigDecimal("10.00"), null, PaymentMethod.UPI, "paypal", null)))
                    .isInstanceOf(PaymentException.class)
                    .satisfies(ex -> assertThat(((PaymentException) ex).getStatus())
                            .isEqualTo(HttpStatus.BAD_REQUEST));
        }
    }

    // ============================= Test double =====================================================

    /**
     * Real Razorpay adapter (real HMAC verification) that records every gateway request, whether a
     * transaction was open when it arrived, and can be told to decline, fail or throw.
     */
    private static final class SpyGateway extends RazorpayGatewayAdapter {
        final List<GatewayChargeRequest> charges = new ArrayList<>();
        final List<GatewayRefundRequest> refunds = new ArrayList<>();
        final List<Boolean> chargeInTransaction = new ArrayList<>();
        final List<Boolean> refundInTransaction = new ArrayList<>();
        final List<Boolean> transferInTransaction = new ArrayList<>();
        Consumer<GatewayChargeRequest> onCharge = req -> { };
        boolean chargeAccepted = true;
        RuntimeException chargeError;
        Consumer<GatewayRefundRequest> onRefund = req -> { };
        boolean refundSucceeds = true;
        RuntimeException refundError;
        boolean transferSucceeds = true;

        SpyGateway() {
            super(RAZORPAY_SECRET);
        }

        @Override
        public GatewayChargeResult charge(GatewayChargeRequest request) {
            charges.add(request);
            chargeInTransaction.add(MarkingTransactionOperations.inTransaction());
            onCharge.accept(request);
            if (chargeError != null) {
                throw chargeError;
            }
            GatewayChargeResult result = super.charge(request);
            return new GatewayChargeResult(result.gatewayReference(), chargeAccepted);
        }

        @Override
        public GatewayRefundResult refund(GatewayRefundRequest request) {
            refunds.add(request);
            refundInTransaction.add(MarkingTransactionOperations.inTransaction());
            onRefund.accept(request);
            if (refundError != null) {
                throw refundError;
            }
            GatewayRefundResult result = super.refund(request);
            return new GatewayRefundResult(result.gatewayRefundReference(), refundSucceeds);
        }

        @Override
        public GatewayTransferResult transfer(GatewayTransferRequest request) {
            transferInTransaction.add(MarkingTransactionOperations.inTransaction());
            GatewayTransferResult result = super.transfer(request);
            return new GatewayTransferResult(result.gatewayTransferReference(), transferSucceeds);
        }
    }
}
