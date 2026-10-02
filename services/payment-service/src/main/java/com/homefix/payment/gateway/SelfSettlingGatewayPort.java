package com.homefix.payment.gateway;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Optional capability of a {@link PaymentGatewayPort} that confirms its own charges instead of
 * sending an asynchronous webhook (Requirement 12.5). Only the local {@link SimulatorGatewayAdapter}
 * implements it: no real gateway can reach a developer machine with a signed callback, so without
 * this every local payment would stay PENDING forever.
 *
 * <p>The gateway does not settle anything itself. It produces the signed callback a real gateway
 * would have sent, and {@code PaymentService} feeds it through {@code handleGatewayCallback}, so
 * signature verification, the payload binding checks, the SUCCESS transition, the PaymentCompleted
 * outbox row, invoice triggering and the provider wallet credit all run on the real code path.
 */
public interface SelfSettlingGatewayPort extends PaymentGatewayPort {

    /**
     * Builds the signed SUCCESS callback for an accepted charge.
     *
     * @param transactionId the HomeFix transaction id the charge was made for
     * @param amount        the charged amount, which the signed payload must repeat exactly
     * @return the payload (in the {@code SignedCallbackPayload} wire format) and its signature
     */
    SignedSettlement settlementFor(UUID transactionId, BigDecimal amount);

    /** A callback payload and the signature over it, as a gateway would deliver them. */
    record SignedSettlement(String payload, String signature) {
    }
}
