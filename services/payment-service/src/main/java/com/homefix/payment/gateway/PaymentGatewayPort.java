package com.homefix.payment.gateway;

/**
 * Abstraction over a payment gateway (Requirement 12.1). The Payment Service depends only on this
 * port; adding support for a new gateway requires only a new implementation and no change to the
 * core business logic ({@code PaymentService}).
 *
 * <p>Each adapter is responsible for the gateway-specific wire protocol: initiating a charge,
 * verifying the cryptographic signature of asynchronous callbacks (Requirement 12.5), initiating
 * refunds (Requirement 12.7), and initiating settlement bank transfers (Requirement 14.3).
 */
public interface PaymentGatewayPort {

    /** @return the stable gateway identifier (e.g. {@code "razorpay"}, {@code "stripe"}). */
    String gatewayId();

    /**
     * Initiates a charge with the gateway.
     *
     * @return a result carrying the gateway's reference for the charge.
     */
    GatewayChargeResult charge(GatewayChargeRequest request);

    /**
     * Verifies the cryptographic signature of a gateway callback (Requirement 12.5). Adapters use
     * the gateway's documented signing scheme (typically HMAC-SHA256 over the raw payload).
     *
     * @return {@code true} if the signature is valid for the given payload.
     */
    boolean verifyCallbackSignature(String payload, String signature);

    /**
     * Initiates a refund of {@code amount} against a prior charge (Requirement 12.7).
     *
     * @return a result carrying the gateway's refund reference.
     */
    GatewayRefundResult refund(GatewayRefundRequest request);

    /**
     * Initiates a settlement bank transfer to a provider's account (Requirement 14.3).
     *
     * @return a result indicating whether the transfer was accepted for processing.
     */
    GatewayTransferResult transfer(GatewayTransferRequest request);
}
