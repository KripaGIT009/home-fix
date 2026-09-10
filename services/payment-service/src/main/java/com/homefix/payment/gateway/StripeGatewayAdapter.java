package com.homefix.payment.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stripe {@link PaymentGatewayPort} adapter (Requirement 12.1). Verifies webhook callbacks with
 * HMAC-SHA256 over the raw payload using the Stripe signing secret, matching Stripe's documented
 * signing scheme. Charge/refund/transfer initiation is simulated here for dev/test; a production
 * build would delegate to the Stripe SDK behind this same port.
 */
@Component
public class StripeGatewayAdapter extends AbstractHmacGatewayAdapter {

    public static final String GATEWAY_ID = "stripe";

    public StripeGatewayAdapter(
            @Value("${homefix.payment.gateways.stripe.webhook-secret:stripe-dev-secret}") String webhookSecret) {
        super(webhookSecret);
    }

    @Override
    public String gatewayId() {
        return GATEWAY_ID;
    }
}
