package com.homefix.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Focused tests for cryptographic callback signature verification (Requirement 12.5) and the
 * gateway adapters that reuse it (Requirement 12.1).
 */
class GatewaySignatureTest {

    private static final String SECRET = "webhook-secret-123";

    @Test
    void hmac_verifiesMatchingSignature() {
        String payload = "{\"event\":\"payment.captured\",\"id\":\"pay_abc\"}";
        String sig = HmacSignatures.hmacSha256Hex(SECRET, payload);
        assertThat(HmacSignatures.verify(SECRET, payload, sig)).isTrue();
    }

    @Test
    void hmac_rejectsTamperedPayload() {
        String payload = "{\"amount\":100}";
        String sig = HmacSignatures.hmacSha256Hex(SECRET, payload);
        assertThat(HmacSignatures.verify(SECRET, "{\"amount\":9999}", sig)).isFalse();
    }

    @Test
    void hmac_rejectsWrongSecret() {
        String payload = "hello";
        String sig = HmacSignatures.hmacSha256Hex(SECRET, payload);
        assertThat(HmacSignatures.verify("different-secret", payload, sig)).isFalse();
    }

    @Test
    void hmac_rejectsNulls() {
        assertThat(HmacSignatures.verify(SECRET, null, "x")).isFalse();
        assertThat(HmacSignatures.verify(SECRET, "x", null)).isFalse();
    }

    @Test
    void razorpayAdapter_verifiesItsOwnSignature() {
        RazorpayGatewayAdapter adapter = new RazorpayGatewayAdapter(SECRET);
        String payload = "razorpay-callback-body";
        String sig = HmacSignatures.hmacSha256Hex(SECRET, payload);
        assertThat(adapter.gatewayId()).isEqualTo("razorpay");
        assertThat(adapter.verifyCallbackSignature(payload, sig)).isTrue();
        assertThat(adapter.verifyCallbackSignature(payload, "bogus")).isFalse();
    }

    @Test
    void stripeAdapter_verifiesItsOwnSignature() {
        StripeGatewayAdapter adapter = new StripeGatewayAdapter(SECRET);
        String payload = "stripe-callback-body";
        String sig = HmacSignatures.hmacSha256Hex(SECRET, payload);
        assertThat(adapter.gatewayId()).isEqualTo("stripe");
        assertThat(adapter.verifyCallbackSignature(payload, sig)).isTrue();
        assertThat(adapter.verifyCallbackSignature(payload, "bogus")).isFalse();
    }

    @Test
    void differentGatewaySecrets_doNotCrossVerify() {
        RazorpayGatewayAdapter razorpay = new RazorpayGatewayAdapter("razorpay-secret");
        StripeGatewayAdapter stripe = new StripeGatewayAdapter("stripe-secret");
        String payload = "shared-body";
        String razorpaySig = HmacSignatures.hmacSha256Hex("razorpay-secret", payload);
        // A signature valid for Razorpay must not verify against Stripe's secret.
        assertThat(stripe.verifyCallbackSignature(payload, razorpaySig)).isFalse();
        assertThat(razorpay.verifyCallbackSignature(payload, razorpaySig)).isTrue();
    }
}
