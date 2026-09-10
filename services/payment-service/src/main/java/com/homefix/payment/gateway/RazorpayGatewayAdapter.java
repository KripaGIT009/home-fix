package com.homefix.payment.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Razorpay {@link PaymentGatewayPort} adapter (Requirement 12.1). Verifies webhook callbacks with
 * HMAC-SHA256 over the raw payload using the Razorpay webhook secret, matching Razorpay's
 * documented signing scheme. Charge/refund/transfer initiation is simulated here for dev/test; a
 * production build would delegate to the Razorpay SDK behind this same port.
 */
@Component
public class RazorpayGatewayAdapter extends AbstractHmacGatewayAdapter {

    public static final String GATEWAY_ID = "razorpay";

    public RazorpayGatewayAdapter(
            @Value("${homefix.payment.gateways.razorpay.webhook-secret:razorpay-dev-secret}") String webhookSecret) {
        super(webhookSecret);
    }

    @Override
    public String gatewayId() {
        return GATEWAY_ID;
    }
}
