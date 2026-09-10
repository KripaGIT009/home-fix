package com.homefix.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.homefix.payment.alert.FinanceAlertPort;
import com.homefix.payment.config.PaymentProperties;
import com.homefix.payment.crypto.KmsEncryptionPort;
import com.homefix.payment.crypto.LocalAesKmsAdapter;
import com.homefix.payment.domain.PaymentMethod;
import com.homefix.payment.domain.PaymentTransaction;
import com.homefix.payment.domain.Settlement;
import com.homefix.payment.domain.SettlementStatus;
import com.homefix.payment.domain.TransactionStatus;
import com.homefix.payment.gateway.HmacSignatures;
import com.homefix.payment.gateway.PaymentGatewayPort;
import com.homefix.payment.gateway.PaymentGatewayRegistry;
import com.homefix.payment.gateway.RazorpayGatewayAdapter;
import com.homefix.payment.gateway.StripeGatewayAdapter;
import com.homefix.payment.idempotency.InMemoryIdempotencyStoreAdapter;
import com.homefix.payment.invoice.InvoiceTriggerException;
import com.homefix.payment.invoice.InvoiceTriggerPort;
import com.homefix.payment.notification.ProviderNotificationPort;
import com.homefix.payment.support.InMemoryPaymentTransactionRepository;
import com.homefix.payment.support.InMemorySettlementRepository;
import com.homefix.payment.support.RecordingPaymentCompletedPublisher;
import com.homefix.payment.wallet.ProviderWalletClientPort;
import com.homefix.payment.wallet.WalletCreditException;

/**
 * Unit tests for the payment domain logic (Requirement 12 and 14.3-14.4).
 *
 * <p>Tests operate against in-memory fakes, a recording outbox publisher, real HMAC gateway
 * adapters, and a real local KMS adapter — no Spring context, database, network, or Redis required.
 * These are example-based tests; Properties 11/12/13 have a dedicated PBT task (Task 41).
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final String TEST_DATA_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String RAZORPAY_SECRET = "razorpay-test-secret";
    private static final String STRIPE_SECRET = "stripe-test-secret";

    private InMemoryPaymentTransactionRepository transactionRepository;
    private InMemorySettlementRepository settlementRepository;
    private InMemoryIdempotencyStoreAdapter idempotencyStore;
    private RecordingPaymentCompletedPublisher publisher;
    private KmsEncryptionPort kms;
    private PaymentGatewayRegistry gatewayRegistry;
    private PaymentProperties props;

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
        settlementRepository = new InMemorySettlementRepository();
        idempotencyStore = new InMemoryIdempotencyStoreAdapter();
        publisher = new RecordingPaymentCompletedPublisher();
        kms = new LocalAesKmsAdapter(TEST_DATA_KEY);

        PaymentGatewayPort razorpay = new RazorpayGatewayAdapter(RAZORPAY_SECRET);
        PaymentGatewayPort stripe = new StripeGatewayAdapter(STRIPE_SECRET);
        gatewayRegistry = new PaymentGatewayRegistry(List.of(razorpay, stripe));

        props = new PaymentProperties();
        props.setRetryBackoff(Duration.ZERO); // keep tests fast — no real backoff sleeps

        service = new PaymentService(transactionRepository, settlementRepository, gatewayRegistry,
                idempotencyStore, kms, walletClient, invoiceTrigger, publisher, financeAlert,
                providerNotification, props);
    }

    private InitiatePaymentCommand paymentCmd(UUID customerId, UUID bookingId, UUID providerId,
                                              String amount, String platformFee) {
        return new InitiatePaymentCommand(customerId, bookingId, providerId,
                new BigDecimal(amount), platformFee == null ? null : new BigDecimal(platformFee),
                PaymentMethod.UPI, RazorpayGatewayAdapter.GATEWAY_ID, null);
    }

    private PaymentTransaction initiate(String amount, String platformFee) {
        return service.initiatePayment(paymentCmd(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), amount, platformFee));
    }

    /** Builds a valid Razorpay callback signature for a payload. */
    private String razorpaySign(String payload) {
        return HmacSignatures.hmacSha256Hex(RAZORPAY_SECRET, payload);
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

            // Same transaction id returned; only one record persisted.
            assertThat(second.getId()).isEqualTo(first.getId());
            assertThat(transactionRepository.findAll()).hasSize(1);
        }

        @Test
        void duplicateAfterSuccess_returnsOriginalStatus_withNoNewCharge() {
            UUID customerId = UUID.randomUUID();
            UUID bookingId = UUID.randomUUID();
            UUID providerId = UUID.randomUUID();

            PaymentTransaction tx = service.initiatePayment(
                    paymentCmd(customerId, bookingId, providerId, "100.00", "20.00"));
            String payload = "{\"txn\":\"" + tx.getId() + "\"}";
            service.handleGatewayCallback(tx.getId(), new GatewayCallback(
                    RazorpayGatewayAdapter.GATEWAY_ID, payload, razorpaySign(payload), true, null));

            String originalRef = transactionRepository.findById(tx.getId()).orElseThrow()
                    .getGatewayReference();

            PaymentTransaction duplicate = service.initiatePayment(
                    paymentCmd(customerId, bookingId, providerId, "100.00", "20.00"));

            assertThat(duplicate.getId()).isEqualTo(tx.getId());
            assertThat(duplicate.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
            // No new charge: the gateway reference is unchanged.
            assertThat(duplicate.getGatewayReference()).isEqualTo(originalRef);
            assertThat(transactionRepository.findAll()).hasSize(1);
        }
    }

    // ============================= Callback signature (Req 12.5) =================================

    @Nested
    class CallbackSignature {

        @Test
        void invalidSignature_isRejected_andStateUnchanged() {
            PaymentTransaction tx = initiate("100.00", "20.00");
            String payload = "{\"txn\":\"" + tx.getId() + "\"}";

            assertThatThrownBy(() -> service.handleGatewayCallback(tx.getId(), new GatewayCallback(
                    RazorpayGatewayAdapter.GATEWAY_ID, payload, "deadbeef-not-a-valid-sig", true, null)))
                    .isInstanceOf(PaymentException.class)
                    .satisfies(ex -> {
                        PaymentException pe = (PaymentException) ex;
                        assertThat(pe.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(pe.getErrorCode()).isEqualTo("INVALID_CALLBACK_SIGNATURE");
                    });

            // State remains PENDING; no event published; no wallet credit.
            assertThat(transactionRepository.findById(tx.getId()).orElseThrow().getStatus())
                    .isEqualTo(TransactionStatus.PENDING);
            assertThat(publisher.published()).isEmpty();
            verify(walletClient, never()).creditEarning(any(), any(), any(), any(), any());
        }

        @Test
        void validSignature_transitionsToSuccess() {
            PaymentTransaction tx = initiate("100.00", "20.00");
            String payload = "{\"txn\":\"" + tx.getId() + "\"}";

            PaymentTransaction result = service.handleGatewayCallback(tx.getId(), new GatewayCallback(
                    RazorpayGatewayAdapter.GATEWAY_ID, payload, razorpaySign(payload), true, null));

            assertThat(result.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
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
            String payload = "ok:" + tx.getId();

            service.handleGatewayCallback(tx.getId(), new GatewayCallback(
                    RazorpayGatewayAdapter.GATEWAY_ID, payload, razorpaySign(payload), true, null));

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

            String payload = "ok:" + tx.getId();
            service.handleGatewayCallback(tx.getId(), new GatewayCallback(
                    RazorpayGatewayAdapter.GATEWAY_ID, payload, razorpaySign(payload), true, null));

            verify(walletClient).creditEarning(eq(providerId), eq(tx.getBookingId()),
                    eq(new BigDecimal("200.00")), eq(new BigDecimal("40.00")),
                    eq(new BigDecimal("160.00")));
        }

        @Test
        void success_publishesPaymentCompletedAndTriggersInvoice() {
            PaymentTransaction tx = initiate("50.00", "5.00");
            String payload = "ok:" + tx.getId();

            service.handleGatewayCallback(tx.getId(), new GatewayCallback(
                    RazorpayGatewayAdapter.GATEWAY_ID, payload, razorpaySign(payload), true, null));

            assertThat(publisher.published()).extracting(PaymentTransaction::getId).containsExactly(tx.getId());
            verify(invoiceTrigger).triggerInvoiceGeneration(tx.getId(), tx.getBookingId());
        }

        @Test
        void walletCreditFailure_retriesThenAlertsFinanceAdmin() {
            props.setMaxWalletCreditRetries(3);
            UUID providerId = UUID.randomUUID();
            PaymentTransaction tx = service.initiatePayment(paymentCmd(
                    UUID.randomUUID(), UUID.randomUUID(), providerId, "100.00", "10.00"));
            String payload = "ok:" + tx.getId();

            doThrow(new WalletCreditException("wallet down"))
                    .when(walletClient).creditEarning(any(), any(), any(), any(), any());

            service.handleGatewayCallback(tx.getId(), new GatewayCallback(
                    RazorpayGatewayAdapter.GATEWAY_ID, payload, razorpaySign(payload), true, null));

            // 3 attempts made, then Finance_Admin alerted.
            verify(walletClient, times(3)).creditEarning(any(), any(), any(), any(), any());
            verify(financeAlert).walletCreditFailed(eq(providerId), eq(tx.getBookingId()),
                    eq(new BigDecimal("90.00")), any());
        }

        @Test
        void invoiceTriggerFailure_retriesUpToMax() {
            props.setMaxInvoiceRetries(3);
            PaymentTransaction tx = initiate("100.00", "10.00");
            String payload = "ok:" + tx.getId();

            doThrow(new InvoiceTriggerException("invoice down"))
                    .when(invoiceTrigger).triggerInvoiceGeneration(any(), any());

            // Does not throw; the payment still succeeds even if invoice trigger ultimately fails.
            service.handleGatewayCallback(tx.getId(), new GatewayCallback(
                    RazorpayGatewayAdapter.GATEWAY_ID, payload, razorpaySign(payload), true, null));

            verify(invoiceTrigger, times(3)).triggerInvoiceGeneration(any(), any());
            assertThat(transactionRepository.findById(tx.getId()).orElseThrow().getStatus())
                    .isEqualTo(TransactionStatus.SUCCESS);
        }
    }

    // ============================= State machine (Req 12.4, Property 12) ==========================

    @Nested
    class StateMachine {

        @Test
        void failedIsTerminal_cannotTransition() {
            PaymentTransaction tx = initiate("100.00", "10.00");
            String payload = "fail:" + tx.getId();
            service.handleGatewayCallback(tx.getId(), new GatewayCallback(
                    RazorpayGatewayAdapter.GATEWAY_ID, payload, razorpaySign(payload), false, "card declined"));

            PaymentTransaction failed = transactionRepository.findById(tx.getId()).orElseThrow();
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
            assertThatThrownBy(() -> service.refund(tx.getId(), new BigDecimal("10.00")))
                    .isInstanceOf(PaymentException.class)
                    .satisfies(ex -> assertThat(((PaymentException) ex).getStatus())
                            .isEqualTo(HttpStatus.CONFLICT));
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

        private PaymentTransaction succeed(String amount, String fee) {
            PaymentTransaction tx = initiate(amount, fee);
            String payload = "ok:" + tx.getId();
            return service.handleGatewayCallback(tx.getId(), new GatewayCallback(
                    RazorpayGatewayAdapter.GATEWAY_ID, payload, razorpaySign(payload), true, null));
        }

        @Test
        void fullRefund_movesToRefunded() {
            PaymentTransaction tx = succeed("100.00", "10.00");
            PaymentTransaction refunded = service.refund(tx.getId(), new BigDecimal("100.00"));
            assertThat(refunded.getStatus()).isEqualTo(TransactionStatus.REFUNDED);
            assertThat(refunded.getRefundedAmount()).isEqualByComparingTo("100.00");
        }

        @Test
        void partialRefund_movesToPartiallyRefunded() {
            PaymentTransaction tx = succeed("100.00", "10.00");
            PaymentTransaction refunded = service.refund(tx.getId(), new BigDecimal("40.00"));
            assertThat(refunded.getStatus()).isEqualTo(TransactionStatus.PARTIALLY_REFUNDED);
            assertThat(refunded.getRefundedAmount()).isEqualByComparingTo("40.00");
        }

        @Test
        void gatewayRefundFailure_alertsFinanceAdmin_andDoesNotChangeState() {
            // A gateway whose refund always fails.
            PaymentGatewayPort failing = new StripeGatewayAdapter(STRIPE_SECRET) {
                @Override
                public com.homefix.payment.gateway.GatewayRefundResult refund(
                        com.homefix.payment.gateway.GatewayRefundRequest request) {
                    return new com.homefix.payment.gateway.GatewayRefundResult("stripe_rf_x", false);
                }
            };
            PaymentGatewayRegistry registry = new PaymentGatewayRegistry(List.of(failing));
            PaymentService svc = new PaymentService(transactionRepository, settlementRepository, registry,
                    idempotencyStore, kms, walletClient, invoiceTrigger, publisher, financeAlert,
                    providerNotification, props);

            PaymentTransaction tx = svc.initiatePayment(new InitiatePaymentCommand(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    new BigDecimal("100.00"), new BigDecimal("10.00"),
                    PaymentMethod.CREDIT_DEBIT_CARD, StripeGatewayAdapter.GATEWAY_ID, null));
            String payload = "ok:" + tx.getId();
            svc.handleGatewayCallback(tx.getId(), new GatewayCallback(
                    StripeGatewayAdapter.GATEWAY_ID, payload,
                    HmacSignatures.hmacSha256Hex(STRIPE_SECRET, payload), true, null));

            assertThatThrownBy(() -> svc.refund(tx.getId(), new BigDecimal("100.00")))
                    .isInstanceOf(PaymentException.class)
                    .satisfies(ex -> assertThat(((PaymentException) ex).getErrorCode())
                            .isEqualTo("REFUND_GATEWAY_ERROR"));

            verify(financeAlert).refundFailed(eq(tx.getId()), eq(tx.getBookingId()),
                    eq(new BigDecimal("100.00")), any());
            // State remains SUCCESS since the refund did not complete.
            assertThat(transactionRepository.findById(tx.getId()).orElseThrow().getStatus())
                    .isEqualTo(TransactionStatus.SUCCESS);
        }
    }

    // ============================= Settlement (Req 14.3, 14.4) ===================================

    @Nested
    class SettlementFlow {

        @Test
        void successfulTransfer_completesSettlement_andEncryptsBankRef() {
            UUID providerId = UUID.randomUUID();
            Settlement settlement = service.initiateSettlement(providerId, new BigDecimal("500.00"),
                    "ACCT-999", RazorpayGatewayAdapter.GATEWAY_ID);

            assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.COMPLETED);
            assertThat(settlement.getBankAccountRefEncrypted())
                    .isNotEqualTo("ACCT-999")
                    .startsWith("v1:");
            assertThat(kms.decrypt(settlement.getBankAccountRefEncrypted())).isEqualTo("ACCT-999");
        }

        @Test
        void failedTransfer_marksFailed_creditsBack_notifiesProviderAndFinance() {
            UUID providerId = UUID.randomUUID();
            PaymentGatewayPort failing = new RazorpayGatewayAdapter(RAZORPAY_SECRET) {
                @Override
                public com.homefix.payment.gateway.GatewayTransferResult transfer(
                        com.homefix.payment.gateway.GatewayTransferRequest request) {
                    return new com.homefix.payment.gateway.GatewayTransferResult("razorpay_tr_x", false);
                }
            };
            PaymentGatewayRegistry registry = new PaymentGatewayRegistry(List.of(failing));
            PaymentService svc = new PaymentService(transactionRepository, settlementRepository, registry,
                    idempotencyStore, kms, walletClient, invoiceTrigger, publisher, financeAlert,
                    providerNotification, props);

            Settlement settlement = svc.initiateSettlement(providerId, new BigDecimal("500.00"),
                    "ACCT-999", RazorpayGatewayAdapter.GATEWAY_ID);

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
            assertThat(gatewayRegistry.registeredGateways())
                    .contains(RazorpayGatewayAdapter.GATEWAY_ID, StripeGatewayAdapter.GATEWAY_ID);
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
}
