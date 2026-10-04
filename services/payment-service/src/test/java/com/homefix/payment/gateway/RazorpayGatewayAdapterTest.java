package com.homefix.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.homefix.payment.domain.PaymentMethod;
import com.homefix.payment.gateway.RazorpayGatewayAdapter.RazorpayPayment;

/**
 * Tests for {@link RazorpayGatewayAdapter} against a mock Razorpay API: what is sent (amounts in
 * paise, the transaction id as receipt, the key pair as basic auth), how the Checkout signature is
 * checked, and how refunds find the order's payment and avoid refunding twice.
 */
class RazorpayGatewayAdapterTest {

    private static final String API = "https://api.razorpay.com/v1";
    private static final String KEY_ID = "rzp_test_key";
    private static final String KEY_SECRET = "test_key_secret";
    private static final String WEBHOOK_SECRET = "test_webhook_secret";

    private MockRestServiceServer server;
    private RazorpayGatewayAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(API);
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new RazorpayGatewayAdapter(KEY_ID, KEY_SECRET, WEBHOOK_SECRET, builder);
    }

    @Test
    void charge_createsAnOrderForTheAmountInPaise_withTheTransactionAsReceipt() {
        UUID transactionId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        String auth = "Basic " + Base64.getEncoder().encodeToString(
                (KEY_ID + ":" + KEY_SECRET).getBytes(StandardCharsets.UTF_8));
        server.expect(requestTo(API + "/orders"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", auth))
                .andExpect(jsonPath("$.amount").value(129999))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.receipt").value(transactionId.toString()))
                .andExpect(jsonPath("$.notes.transactionId").value(transactionId.toString()))
                .andExpect(jsonPath("$.notes.bookingId").value(bookingId.toString()))
                .andRespond(withSuccess("{\"id\":\"order_123\",\"amount\":129999,\"currency\":\"INR\"}",
                        MediaType.APPLICATION_JSON));

        GatewayChargeResult result = adapter.charge(new GatewayChargeRequest(
                transactionId, bookingId, UUID.randomUUID(), new BigDecimal("1299.99"), PaymentMethod.UPI));

        assertThat(result.gatewayReference()).isEqualTo("order_123");
        assertThat(result.accepted()).isTrue();
        server.verify();
    }

    @Test
    void withoutKeys_isNotReady_andRefusesToCharge() {
        RazorpayGatewayAdapter unconfigured =
                new RazorpayGatewayAdapter("", "", WEBHOOK_SECRET, RestClient.builder().baseUrl(API));

        assertThat(unconfigured.isReady()).isFalse();
        assertThatThrownBy(() -> unconfigured.charge(new GatewayChargeRequest(UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN, PaymentMethod.UPI)))
                .isInstanceOf(IllegalStateException.class);
        // The webhook secret still works without the key pair.
        String sig = HmacSignatures.hmacSha256Hex(WEBHOOK_SECRET, "{}");
        assertThat(unconfigured.verifyCallbackSignature("{}", sig)).isTrue();
    }

    @Test
    void checkoutSignature_isHmacOfOrderAndPaymentWithTheKeySecret() {
        String valid = HmacSignatures.hmacSha256Hex(KEY_SECRET, "order_1|pay_1");

        assertThat(adapter.verifyCheckoutSignature("order_1", "pay_1", valid)).isTrue();
        assertThat(adapter.verifyCheckoutSignature("order_2", "pay_1", valid)).isFalse();
        assertThat(adapter.verifyCheckoutSignature("order_1", "pay_2", valid)).isFalse();
        // Signed with the webhook secret instead of the key secret.
        assertThat(adapter.verifyCheckoutSignature("order_1", "pay_1",
                HmacSignatures.hmacSha256Hex(WEBHOOK_SECRET, "order_1|pay_1"))).isFalse();
        assertThat(adapter.verifyCheckoutSignature("order_1", "pay_1", null)).isFalse();
    }

    @Test
    void fetchPayment_readsOrderAmountAndStatus() {
        server.expect(requestTo(API + "/payments/pay_1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id":"pay_1","order_id":"order_1","status":"captured","amount":50000,
                         "currency":"INR","created_at":1790000000}""", MediaType.APPLICATION_JSON));

        RazorpayPayment payment = adapter.fetchPayment("pay_1");

        assertThat(payment.orderId()).isEqualTo("order_1");
        assertThat(payment.amountPaise()).isEqualTo(50000);
        assertThat(payment.isCaptured()).isTrue();
        assertThat(payment.currency()).isEqualTo("INR");
    }

    @Test
    void refund_refundsTheOrdersCapturedPayment_withTheRefundIdAsReceipt() {
        server.expect(requestTo(API + "/orders/order_1/payments"))
                .andRespond(withSuccess("""
                        {"items":[{"id":"pay_failed","status":"failed"},{"id":"pay_1","status":"captured"}]}""",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "/payments/pay_1/refunds"))
                .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "/payments/pay_1/refund"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.amount").value(5000))
                .andExpect(jsonPath("$.receipt").value("refund-1"))
                .andRespond(withSuccess("{\"id\":\"rfnd_1\",\"status\":\"processed\"}", MediaType.APPLICATION_JSON));

        GatewayRefundResult result = adapter.refund(new GatewayRefundRequest("order_1", new BigDecimal("50.00"), "refund-1"));

        assertThat(result.succeeded()).isTrue();
        assertThat(result.gatewayRefundReference()).isEqualTo("rfnd_1");
        server.verify();
    }

    @Test
    void refund_resent_returnsTheExistingRefundInsteadOfRefundingAgain() {
        server.expect(requestTo(API + "/orders/order_1/payments"))
                .andRespond(withSuccess("{\"items\":[{\"id\":\"pay_1\",\"status\":\"captured\"}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "/payments/pay_1/refunds"))
                .andRespond(withSuccess("{\"items\":[{\"id\":\"rfnd_1\",\"receipt\":\"refund-1\"}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(never(), requestTo(API + "/payments/pay_1/refund"));

        GatewayRefundResult result = adapter.refund(new GatewayRefundRequest("order_1", new BigDecimal("50.00"), "refund-1"));

        assertThat(result.succeeded()).isTrue();
        assertThat(result.gatewayRefundReference()).isEqualTo("rfnd_1");
        server.verify();
    }

    @Test
    void refund_refusedByRazorpay_isARejection() {
        server.expect(requestTo(API + "/orders/order_1/payments"))
                .andRespond(withSuccess("{\"items\":[{\"id\":\"pay_1\",\"status\":\"captured\"}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "/payments/pay_1/refunds"))
                .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "/payments/pay_1/refund"))
                .andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":\"BAD_REQUEST_ERROR\"}}"));

        GatewayRefundResult result = adapter.refund(new GatewayRefundRequest("order_1", new BigDecimal("50.00"), "refund-1"));

        assertThat(result.succeeded()).isFalse();
    }

    @Test
    void paiseConversion_isExact() {
        assertThat(RazorpayGatewayAdapter.toPaise(new BigDecimal("1299.99"))).isEqualTo(129999);
        assertThat(RazorpayGatewayAdapter.toPaise(new BigDecimal("500"))).isEqualTo(50000);
        assertThat(RazorpayGatewayAdapter.fromPaise(129999)).isEqualByComparingTo("1299.99");
    }
}
