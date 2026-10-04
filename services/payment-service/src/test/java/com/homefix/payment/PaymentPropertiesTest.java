package com.homefix.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.homefix.payment.config.PaymentProperties;
import com.homefix.payment.crypto.KmsEncryptionPort;
import com.homefix.payment.crypto.LocalAesKmsAdapter;
import com.homefix.payment.domain.PaymentMethod;
import com.homefix.payment.domain.PaymentTransaction;
import com.homefix.payment.domain.TransactionStatus;
import com.homefix.payment.gateway.AbstractHmacGatewayAdapter;
import com.homefix.payment.gateway.HmacSignatures;
import com.homefix.payment.gateway.PaymentGatewayPort;
import com.homefix.payment.gateway.PaymentGatewayRegistry;
import com.homefix.payment.gateway.RazorpayGatewayAdapter;
import com.homefix.payment.idempotency.InMemoryIdempotencyStoreAdapter;
import com.homefix.payment.invoice.InvoiceTriggerPort;
import com.homefix.payment.notification.ProviderNotificationPort;
import com.homefix.payment.service.GatewayCallback;
import com.homefix.payment.service.InitiatePaymentCommand;
import com.homefix.payment.service.PaymentException;
import com.homefix.payment.service.PaymentService;
import com.homefix.payment.support.InMemoryPaymentRefundRepository;
import com.homefix.payment.support.InMemoryPaymentTransactionRepository;
import com.homefix.payment.support.InMemorySettlementRepository;
import com.homefix.payment.support.MarkingTransactionOperations;
import com.homefix.payment.support.RecordingPaymentCompletedPublisher;
import com.homefix.payment.wallet.ProviderWalletClientPort;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.BigRange;

/**
 * Property-based tests for the Payment Service correctness properties 11-13 (design.md
 * "Correctness Properties"; Requirements 12.3, 12.4, 12.10). Each property runs a minimum of
 * 100 tries and is tagged with the required {@code Feature: homefix-platform, Property N} label.
 *
 * <p>These complement the example-based {@code PaymentServiceTest} by asserting the properties
 * hold universally across generated inputs. Tests operate against in-memory fakes, a recording
 * outbox publisher, a real HMAC gateway adapter, and a real local KMS adapter — no Spring
 * context, database, network, or Redis required.
 */
class PaymentPropertiesTest {

    private static final String TEST_DATA_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String RAZORPAY_SECRET = "razorpay-test-secret";

    // ============================================================================================
    // Property 11: Payment idempotency (Req 12.3)
    // ============================================================================================

    @Property(tries = 150)
    @Label("Feature: homefix-platform, Property 11: Payment idempotency")
    void duplicateIdempotencyKeyReturnsOriginalAndCreatesNoNewRecord(
            @ForAll @BigRange(min = "1.00", max = "100000.00") BigDecimal amount,
            @ForAll("paymentMethod") PaymentMethod method) {

        Fixture f = new Fixture();
        UUID customerId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();

        BigDecimal amt = amount.setScale(2, java.math.RoundingMode.HALF_UP);
        InitiatePaymentCommand cmd = new InitiatePaymentCommand(customerId, bookingId, providerId,
                amt, null, method, RazorpayGatewayAdapter.GATEWAY_ID, null);

        PaymentTransaction first = f.service.initiatePayment(cmd);

        // Any number of duplicate attempts for the same (customerId, bookingId) must return the
        // original transaction and never create a new record or a new gateway charge.
        String originalRef = first.getGatewayReference();
        TransactionStatus originalStatus = first.getStatus();
        for (int i = 0; i < 3; i++) {
            PaymentTransaction dup = f.service.initiatePayment(cmd);
            assertThat(dup.getId()).isEqualTo(first.getId());
            assertThat(dup.getStatus()).isEqualTo(originalStatus);
            assertThat(dup.getGatewayReference()).isEqualTo(originalRef);
        }
        assertThat(f.transactionRepository.findAll()).hasSize(1);
    }

    // ============================================================================================
    // Property 12: Payment transaction state machine valid transitions only (Req 12.4)
    // ============================================================================================

    // RANDOMIZED generation so the property runs the full configured tries (>= 100) rather than
    // the 25 exhaustive enum×enum combinations jqwik would otherwise cap it at.
    @Property(tries = 150, generation = GenerationMode.RANDOMIZED)
    @Label("Feature: homefix-platform, Property 12: Payment transaction state machine valid transitions only")
    void transitionPermittedIffInDefinedMap(
            @ForAll("transactionStatus") TransactionStatus source,
            @ForAll("transactionStatus") TransactionStatus target) {

        boolean expectedPermitted = permitted(source, target);

        // canTransitionTo is the single source of truth consulted by the entity guard.
        assertThat(source.canTransitionTo(target))
                .as("%s -> %s permitted?", source, target)
                .isEqualTo(expectedPermitted);

        // Exercise the entity guard end-to-end: build a transaction forced into the source state
        // via only-legal steps, then attempt the target and assert accept/reject accordingly.
        PaymentTransaction tx = transactionInState(source);
        if (tx == null) {
            return; // source not reachable by a single-hop path in this harness; guard covered above
        }

        Throwable thrown = catchThrowable(() -> tx.transitionTo(target));
        if (expectedPermitted) {
            assertThat(thrown).as("permitted %s -> %s must not throw", source, target).isNull();
            assertThat(tx.getStatus()).isEqualTo(target);
        } else {
            assertThat(thrown)
                    .as("illegal %s -> %s must be rejected", source, target)
                    .isInstanceOf(PaymentException.class);
            assertThat(tx.getStatus()).isEqualTo(source);
        }
    }

    // ============================================================================================
    // Property 13: Provider wallet credit on payment success (Req 12.10)
    // ============================================================================================

    @Property(tries = 150)
    @Label("Feature: homefix-platform, Property 13: Provider wallet credit on payment success")
    void successCreditsWalletByAmountMinusPlatformFee(
            @ForAll @BigRange(min = "1.00", max = "100000.00") BigDecimal amount,
            @ForAll @BigRange(min = "0.00", max = "100000.00") BigDecimal platformFee) {

        BigDecimal amt = amount.setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal fee = platformFee.setScale(2, java.math.RoundingMode.HALF_UP);
        // Platform fee is constrained by the domain to not exceed the amount.
        if (fee.compareTo(amt) > 0) {
            fee = amt;
        }

        Fixture f = new Fixture();
        UUID providerId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();

        BigDecimal balanceBefore = f.wallet.balanceOf(providerId);

        PaymentTransaction tx = f.service.initiatePayment(new InitiatePaymentCommand(
                customerId, bookingId, providerId, amt, fee, PaymentMethod.UPI,
                RazorpayGatewayAdapter.GATEWAY_ID, null));

        // Drive the transaction to SUCCESS via a valid gateway callback; wallet credit happens
        // synchronously within the callback handling (well within the 60s bound).
        String payload = "{\"eventId\":\"evt_" + UUID.randomUUID() + "\","
                + "\"transactionId\":\"" + tx.getId() + "\","
                + "\"gatewayId\":\"" + RazorpayGatewayAdapter.GATEWAY_ID + "\","
                + "\"status\":\"SUCCEEDED\","
                + "\"amount\":\"" + amt.toPlainString() + "\","
                + "\"timestamp\":\"" + Instant.now() + "\"}";
        f.service.handleGatewayCallback(tx.getId(), new GatewayCallback(
                RazorpayGatewayAdapter.GATEWAY_ID, payload,
                HmacSignatures.hmacSha256Hex(RAZORPAY_SECRET, payload)));

        BigDecimal expectedCredit = amt.subtract(fee);
        BigDecimal balanceAfter = f.wallet.balanceOf(providerId);

        assertThat(balanceAfter.subtract(balanceBefore))
                .as("wallet increases by exactly amount (%s) - platformFee (%s)", amt, fee)
                .isEqualByComparingTo(expectedCredit);
        // Credited exactly once.
        assertThat(f.wallet.creditCount(providerId)).isEqualTo(1);
    }

    // ============================================================================================
    // Generators
    // ============================================================================================

    @Provide
    Arbitrary<PaymentMethod> paymentMethod() {
        return Arbitraries.of(PaymentMethod.values());
    }

    @Provide
    Arbitrary<TransactionStatus> transactionStatus() {
        return Arbitraries.of(TransactionStatus.values());
    }

    // ============================================================================================
    // Helpers
    // ============================================================================================

    private static boolean permitted(TransactionStatus source, TransactionStatus target) {
        return switch (source) {
            case PENDING -> target == TransactionStatus.SUCCESS || target == TransactionStatus.FAILED;
            // A partial refund can be followed by further partial refunds or the remainder.
            case SUCCESS, PARTIALLY_REFUNDED -> target == TransactionStatus.REFUNDED
                    || target == TransactionStatus.PARTIALLY_REFUNDED;
            case FAILED, REFUNDED -> false;
        };
    }

    /**
     * Builds a {@link PaymentTransaction} forced into {@code state} using only legal transitions
     * from PENDING, so the entity guard can be exercised for that source state. PARTIALLY_REFUNDED
     * is reached through a real partial refund, the only way into it.
     */
    private PaymentTransaction transactionInState(TransactionStatus state) {
        PaymentTransaction tx = PaymentTransaction.initiate(
                "cust:" + UUID.randomUUID() + ":booking:" + UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("100.00"), new BigDecimal("10.00"), PaymentMethod.UPI,
                RazorpayGatewayAdapter.GATEWAY_ID, null);
        switch (state) {
            case PENDING:
                return tx;
            case SUCCESS:
                tx.transitionTo(TransactionStatus.SUCCESS);
                return tx;
            case FAILED:
                tx.transitionTo(TransactionStatus.FAILED);
                return tx;
            case REFUNDED:
                tx.transitionTo(TransactionStatus.SUCCESS);
                tx.transitionTo(TransactionStatus.REFUNDED);
                return tx;
            case PARTIALLY_REFUNDED:
                tx.transitionTo(TransactionStatus.SUCCESS);
                tx.applyRefund(new BigDecimal("10.00"));
                return tx;
            default:
                return null;
        }
    }

    /** Wires a fully in-memory PaymentService with a balance-tracking wallet fake. */
    private static final class Fixture {
        final InMemoryPaymentTransactionRepository transactionRepository =
                new InMemoryPaymentTransactionRepository();
        final BalanceTrackingWallet wallet = new BalanceTrackingWallet();
        final PaymentService service;

        Fixture() {
            InMemorySettlementRepository settlementRepository = new InMemorySettlementRepository();
            InMemoryIdempotencyStoreAdapter idempotencyStore = new InMemoryIdempotencyStoreAdapter();
            RecordingPaymentCompletedPublisher publisher = new RecordingPaymentCompletedPublisher();
            KmsEncryptionPort kms = new LocalAesKmsAdapter(TEST_DATA_KEY);
            // A generic HMAC gateway under the razorpay id: these properties are about the payment
            // state machine, not the Razorpay API, which RazorpayGatewayAdapterTest covers.
            PaymentGatewayPort razorpay = new AbstractHmacGatewayAdapter(RAZORPAY_SECRET) {
                @Override
                public String gatewayId() {
                    return RazorpayGatewayAdapter.GATEWAY_ID;
                }
            };
            PaymentGatewayRegistry gatewayRegistry = new PaymentGatewayRegistry(List.of(razorpay));
            InvoiceTriggerPort invoiceTrigger = (paymentId, bookingId) -> { /* no-op */ };
            ProviderNotificationPort providerNotification = new NoopProviderNotification();

            PaymentProperties props = new PaymentProperties();
            props.setRetryBackoff(Duration.ZERO);

            this.service = new PaymentService(transactionRepository,
                    new InMemoryPaymentRefundRepository(), settlementRepository,
                    gatewayRegistry, idempotencyStore, kms, wallet, invoiceTrigger, publisher,
                    new NoopFinanceAlert(), providerNotification, props,
                    new MarkingTransactionOperations());
        }
    }

    /** Wallet fake that accumulates the net-earning credits per provider. */
    private static final class BalanceTrackingWallet implements ProviderWalletClientPort {
        private final ConcurrentHashMap<UUID, BigDecimal> balances = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<UUID, Integer> credits = new ConcurrentHashMap<>();

        @Override
        public void creditEarning(UUID providerId, UUID bookingId, String bookingReference, BigDecimal gross,
                                  BigDecimal platformFee, BigDecimal netAmount) {
            balances.merge(providerId, netAmount, BigDecimal::add);
            credits.merge(providerId, 1, Integer::sum);
        }

        @Override
        public void creditSettlementReversal(UUID providerId, UUID settlementId, BigDecimal amount) {
            balances.merge(providerId, amount, BigDecimal::add);
        }

        BigDecimal balanceOf(UUID providerId) {
            return balances.getOrDefault(providerId, BigDecimal.ZERO);
        }

        int creditCount(UUID providerId) {
            return credits.getOrDefault(providerId, 0);
        }
    }

    private static final class NoopFinanceAlert implements com.homefix.payment.alert.FinanceAlertPort {
        @Override
        public void walletCreditFailed(UUID providerId, UUID bookingId, BigDecimal amount, String reason) {
        }

        @Override
        public void refundFailed(UUID transactionId, UUID bookingId, BigDecimal amount, String reason) {
        }

        @Override
        public void settlementFailed(UUID providerId, UUID settlementId, BigDecimal amount, String reason) {
        }

        @Override
        public void lateCapture(UUID paymentId, UUID bookingId, BigDecimal amount, String reason) {
        }
    }

    private static final class NoopProviderNotification implements ProviderNotificationPort {
        @Override
        public void settlementFailed(UUID providerId, UUID settlementId, BigDecimal amount) {
        }
    }
}
