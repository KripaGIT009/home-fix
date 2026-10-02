package com.homefix.payment.gateway;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Local-only payment gateway, id {@value #GATEWAY_ID} (Requirement 12.1, 12.5). It accepts every
 * charge, refund and transfer, and then confirms the charge itself with a correctly signed SUCCESS
 * callback ({@link SelfSettlingGatewayPort}) that {@code PaymentService} passes through the normal
 * signed-callback path, so a payment made on a developer machine completes end to end.
 *
 * <p><strong>It must never run in production</strong>, because it moves no money while reporting
 * success. The guard is structural: the bean exists only when
 * {@code homefix.payment.gateways.simulator.enabled=true} (env {@code PAYMENT_SIMULATOR_ENABLED},
 * default {@code false}), so with the property unset it is not registered and {@code "simulator"} is
 * an unknown gateway (400). The local compose stack enables it; Helm never does. When it is enabled
 * a WARN is logged at startup so an accidental enablement shows in the logs.
 *
 * <p>Its callbacks are signed with its own secret ({@code PAYMENT_SIMULATOR_SECRET}); a dev default
 * applies when none is set, which is acceptable only because the bean cannot exist unless enabled.
 * Anyone who knows the secret could settle a simulator payment through the public callback
 * endpoint, which is harmless locally and impossible where the simulator is not registered.
 */
@Component
@ConditionalOnProperty(prefix = "homefix.payment.gateways.simulator", name = "enabled", havingValue = "true")
public class SimulatorGatewayAdapter extends AbstractHmacGatewayAdapter implements SelfSettlingGatewayPort {

    private static final Logger log = LoggerFactory.getLogger(SimulatorGatewayAdapter.class);

    public static final String GATEWAY_ID = "simulator";

    /** Used only when the simulator is enabled without {@code PAYMENT_SIMULATOR_SECRET}. */
    static final String DEV_DEFAULT_SECRET = "local-payment-simulator-secret";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public SimulatorGatewayAdapter(
            @Value("${homefix.payment.gateways.simulator.secret:}") String signingSecret) {
        super(signingSecret == null || signingSecret.isBlank() ? DEV_DEFAULT_SECRET : signingSecret);
        log.warn("PAYMENT SIMULATOR ENABLED: gateway '{}' accepts every charge and settles it as SUCCESS "
                + "without moving any money. This is for local development only and must never be "
                + "enabled in production (homefix.payment.gateways.simulator.enabled / "
                + "PAYMENT_SIMULATOR_ENABLED).", GATEWAY_ID);
        if (signingSecret == null || signingSecret.isBlank()) {
            log.warn("PAYMENT_SIMULATOR_SECRET is not set; the simulator signs its callbacks with the "
                    + "built-in development secret");
        }
    }

    @Override
    public String gatewayId() {
        return GATEWAY_ID;
    }

    @Override
    public SignedSettlement settlementFor(UUID transactionId, BigDecimal amount) {
        ObjectNode body = MAPPER.createObjectNode()
                .put("eventId", "sim_evt_" + UUID.randomUUID())
                .put("transactionId", transactionId.toString())
                .put("gatewayId", GATEWAY_ID)
                .put("status", "SUCCEEDED")
                .put("amount", amount.toPlainString())
                .put("timestamp", Instant.now().toString());
        String payload;
        try {
            payload = MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialise the simulator callback", e);
        }
        return new SignedSettlement(payload, sign(payload));
    }
}
