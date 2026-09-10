package com.homefix.payment.gateway;

import java.util.UUID;

/**
 * Base {@link PaymentGatewayPort} adapter that supplies real, testable HMAC-SHA256 callback
 * signature verification (Requirement 12.5) and simple simulated charge/refund/transfer
 * initiation suitable for dev and tests. A production adapter for a specific gateway would
 * override the charge/refund/transfer methods to call the gateway SDK while keeping the same
 * signature-verification contract.
 *
 * <p>Because the whole gateway surface sits behind {@link PaymentGatewayPort}, adding a new
 * gateway is only a matter of adding a new subclass — no core business logic changes
 * (Requirement 12.1).
 */
public abstract class AbstractHmacGatewayAdapter implements PaymentGatewayPort {

    private final String signingSecret;

    protected AbstractHmacGatewayAdapter(String signingSecret) {
        this.signingSecret = signingSecret;
    }

    @Override
    public boolean verifyCallbackSignature(String payload, String signature) {
        return HmacSignatures.verify(signingSecret, payload, signature);
    }

    @Override
    public GatewayChargeResult charge(GatewayChargeRequest request) {
        String reference = gatewayId() + "_ch_" + UUID.randomUUID();
        return new GatewayChargeResult(reference, true);
    }

    @Override
    public GatewayRefundResult refund(GatewayRefundRequest request) {
        String reference = gatewayId() + "_rf_" + UUID.randomUUID();
        return new GatewayRefundResult(reference, true);
    }

    @Override
    public GatewayTransferResult transfer(GatewayTransferRequest request) {
        String reference = gatewayId() + "_tr_" + UUID.randomUUID();
        return new GatewayTransferResult(reference, true);
    }

    /** Exposed for adapters/tests that need to produce a valid signature for a payload. */
    protected String sign(String payload) {
        return HmacSignatures.hmacSha256Hex(signingSecret, payload);
    }
}
