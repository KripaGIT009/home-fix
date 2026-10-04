package com.homefix.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.homefix.payment.domain.PaymentMethod;
import com.homefix.payment.domain.PaymentTransaction;
import com.homefix.payment.domain.TransactionStatus;
import com.homefix.payment.gateway.RazorpayGatewayAdapter;
import com.homefix.payment.gateway.RazorpayGatewayAdapter.RazorpayPayment;

/**
 * Tests for {@link RazorpayPaymentService}: a Checkout confirmation or webhook settles the
 * transaction only when it is signed, names this transaction's order and carries its amount as
 * Razorpay itself reports it.
 */
@ExtendWith(MockitoExtension.class)
class RazorpayPaymentServiceTest {

    private static final String ORDER = "order_1";

    @Mock
    private PaymentService paymentService;
    @Mock
    private RazorpayGatewayAdapter razorpay;

    private RazorpayPaymentService service;
    private PaymentTransaction tx;

    @BeforeEach
    void setUp() {
        service = new RazorpayPaymentService(paymentService, razorpay);
        tx = PaymentTransaction.initiate("key-" + UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "HFX-1", UUID.randomUUID(), new BigDecimal("1299.99"), new BigDecimal("260.00"),
                PaymentMethod.UPI, RazorpayGatewayAdapter.GATEWAY_ID, null);
        tx.setGatewayReference(ORDER);
    }

    private static RazorpayPayment payment(String status, long paise) {
        return new RazorpayPayment("pay_1", ORDER, status, paise, "INR", Instant.now());
    }

    @Test
    void confirmCheckout_capturedPayment_settlesWithTheAmountRazorpayReports() {
        when(razorpay.verifyCheckoutSignature(ORDER, "pay_1", "sig")).thenReturn(true);
        when(razorpay.fetchPayment("pay_1")).thenReturn(payment("captured", 129999));
        when(paymentService.settleVerifiedOutcome(eq(tx.getId()), any())).thenReturn(tx);

        service.confirmCheckout(tx, ORDER, "pay_1", "sig");

        ArgumentCaptor<SignedCallbackPayload> outcome = ArgumentCaptor.forClass(SignedCallbackPayload.class);
        verify(paymentService).settleVerifiedOutcome(eq(tx.getId()), outcome.capture());
        assertThat(outcome.getValue().eventId()).isEqualTo("pay_1");
        assertThat(outcome.getValue().transactionId()).isEqualTo(tx.getId());
        assertThat(outcome.getValue().gatewayId()).isEqualTo("razorpay");
        assertThat(outcome.getValue().outcome()).isEqualTo(SignedCallbackPayload.Outcome.SUCCEEDED);
        assertThat(outcome.getValue().amount()).isEqualByComparingTo("1299.99");
    }

    @Test
    void confirmCheckout_authorizedPayment_isCapturedBeforeSettling() {
        when(razorpay.verifyCheckoutSignature(ORDER, "pay_1", "sig")).thenReturn(true);
        when(razorpay.fetchPayment("pay_1")).thenReturn(payment("authorized", 129999));
        when(razorpay.capture("pay_1", 129999)).thenReturn(payment("captured", 129999));
        when(paymentService.settleVerifiedOutcome(eq(tx.getId()), any())).thenReturn(tx);

        service.confirmCheckout(tx, ORDER, "pay_1", "sig");

        verify(razorpay).capture("pay_1", 129999);
        verify(paymentService).settleVerifiedOutcome(eq(tx.getId()), any());
    }

    @Test
    void confirmCheckout_forAnotherOrder_isRejectedBeforeAskingRazorpay() {
        assertThatThrownBy(() -> service.confirmCheckout(tx, "order_other", "pay_1", "sig"))
                .isInstanceOf(PaymentException.class)
                .extracting("errorCode").isEqualTo("CALLBACK_MISMATCH");
        verify(razorpay, never()).fetchPayment(anyString());
        verify(paymentService, never()).settleVerifiedOutcome(any(), any());
    }

    @Test
    void confirmCheckout_withABadSignature_isRejected() {
        when(razorpay.verifyCheckoutSignature(ORDER, "pay_1", "forged")).thenReturn(false);

        assertThatThrownBy(() -> service.confirmCheckout(tx, ORDER, "pay_1", "forged"))
                .isInstanceOf(PaymentException.class)
                .extracting("errorCode").isEqualTo("INVALID_CALLBACK_SIGNATURE");
        verify(razorpay, never()).fetchPayment(anyString());
    }

    @Test
    void confirmCheckout_paymentForASmallerAmount_isRejected() {
        when(razorpay.verifyCheckoutSignature(ORDER, "pay_1", "sig")).thenReturn(true);
        when(razorpay.fetchPayment("pay_1")).thenReturn(payment("captured", 100));

        assertThatThrownBy(() -> service.confirmCheckout(tx, ORDER, "pay_1", "sig"))
                .isInstanceOf(PaymentException.class)
                .extracting("errorCode").isEqualTo("CALLBACK_MISMATCH");
        verify(paymentService, never()).settleVerifiedOutcome(any(), any());
    }

    @Test
    void confirmCheckout_notCapturedYet_leavesTheTransactionPending() {
        when(razorpay.verifyCheckoutSignature(ORDER, "pay_1", "sig")).thenReturn(true);
        when(razorpay.fetchPayment("pay_1")).thenReturn(payment("created", 129999));

        PaymentTransaction result = service.confirmCheckout(tx, ORDER, "pay_1", "sig");

        assertThat(result.getStatus()).isEqualTo(TransactionStatus.PENDING);
        verify(paymentService, never()).settleVerifiedOutcome(any(), any());
        verify(razorpay, never()).capture(anyString(), anyLong());
    }

    @Test
    void confirmCheckout_razorpayUnreachable_is502() {
        when(razorpay.verifyCheckoutSignature(ORDER, "pay_1", "sig")).thenReturn(true);
        when(razorpay.fetchPayment("pay_1")).thenThrow(new IllegalStateException("timeout"));

        assertThatThrownBy(() -> service.confirmCheckout(tx, ORDER, "pay_1", "sig"))
                .isInstanceOf(PaymentException.class)
                .extracting("errorCode").isEqualTo("PAYMENT_GATEWAY_ERROR");
    }

    @Test
    void confirmCheckout_alreadySettled_isANoOp() {
        tx.transitionTo(TransactionStatus.SUCCESS);

        assertThat(service.confirmCheckout(tx, ORDER, "pay_1", "sig")).isSameAs(tx);
        verifyNoInteractions(razorpay, paymentService);
    }

    @Test
    void checkoutFor_pendingRazorpayPayment_givesTheOrderAndAmountInPaise() {
        when(razorpay.isReady()).thenReturn(true);
        when(razorpay.keyId()).thenReturn("rzp_test_key");

        RazorpayPaymentService.RazorpayCheckout checkout = service.checkoutFor(tx).orElseThrow();

        assertThat(checkout.keyId()).isEqualTo("rzp_test_key");
        assertThat(checkout.orderId()).isEqualTo(ORDER);
        assertThat(checkout.amount()).isEqualTo(129999);
        assertThat(checkout.currency()).isEqualTo("INR");
    }

    @Test
    void checkoutFor_settledPayment_isEmpty() {
        tx.transitionTo(TransactionStatus.SUCCESS);

        assertThat(service.checkoutFor(tx)).isEmpty();
    }

    @Test
    void webhook_paymentCaptured_settlesTheOrdersTransaction() {
        String body = """
                {"event":"payment.captured","created_at":1790000000,"payload":{"payment":{"entity":
                {"id":"pay_1","order_id":"order_1","status":"captured","amount":129999,"currency":"INR"}}}}""";
        when(razorpay.verifyCallbackSignature(body, "sig")).thenReturn(true);
        when(paymentService.findByGatewayReference(ORDER)).thenReturn(Optional.of(tx));

        service.handleWebhook(body.getBytes(StandardCharsets.UTF_8), "sig");

        ArgumentCaptor<SignedCallbackPayload> outcome = ArgumentCaptor.forClass(SignedCallbackPayload.class);
        verify(paymentService).settleVerifiedOutcome(eq(tx.getId()), outcome.capture());
        assertThat(outcome.getValue().eventId()).isEqualTo("pay_1");
        assertThat(outcome.getValue().amount()).isEqualByComparingTo("1299.99");
        assertThat(outcome.getValue().timestamp()).isEqualTo(Instant.ofEpochSecond(1790000000));
    }

    @Test
    void webhook_withABadSignature_isRejectedUnread() {
        when(razorpay.verifyCallbackSignature(anyString(), eq("forged"))).thenReturn(false);

        assertThatThrownBy(() -> service.handleWebhook("{\"event\":\"payment.captured\"}"
                .getBytes(StandardCharsets.UTF_8), "forged"))
                .isInstanceOf(PaymentException.class)
                .extracting("errorCode").isEqualTo("INVALID_CALLBACK_SIGNATURE");
        verifyNoInteractions(paymentService);
    }

    @Test
    void webhook_forAnOrderThisServiceDidNotCreate_isIgnored() {
        String body = """
                {"event":"payment.captured","payload":{"payment":{"entity":
                {"id":"pay_9","order_id":"order_elsewhere","status":"captured","amount":100,"currency":"INR"}}}}""";
        when(razorpay.verifyCallbackSignature(body, "sig")).thenReturn(true);
        when(paymentService.findByGatewayReference("order_elsewhere")).thenReturn(Optional.empty());

        service.handleWebhook(body.getBytes(StandardCharsets.UTF_8), "sig");

        verify(paymentService, never()).settleVerifiedOutcome(any(), any());
    }

    @Test
    void webhook_paymentFailed_doesNotFailTheTransaction() {
        String body = """
                {"event":"payment.failed","payload":{"payment":{"entity":
                {"id":"pay_1","order_id":"order_1","status":"failed","amount":129999,"currency":"INR"}}}}""";
        when(razorpay.verifyCallbackSignature(body, "sig")).thenReturn(true);

        service.handleWebhook(body.getBytes(StandardCharsets.UTF_8), "sig");

        verify(paymentService, never()).settleVerifiedOutcome(any(), any());
    }
}
