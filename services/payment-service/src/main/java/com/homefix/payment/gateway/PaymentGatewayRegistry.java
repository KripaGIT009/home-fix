package com.homefix.payment.gateway;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.homefix.payment.service.PaymentException;

/**
 * Resolves a {@link PaymentGatewayPort} by its gateway id. Spring injects every adapter on the
 * classpath, so registering a new gateway is entirely additive: drop in a new adapter component
 * and it becomes selectable here with no change to this class or the core logic (Requirement 12.1).
 */
@Component
public class PaymentGatewayRegistry {

    private final Map<String, PaymentGatewayPort> byId = new LinkedHashMap<>();

    public PaymentGatewayRegistry(List<PaymentGatewayPort> gateways) {
        for (PaymentGatewayPort gateway : gateways) {
            byId.put(gateway.gatewayId(), gateway);
        }
    }

    /**
     * @return the gateway registered under {@code gatewayId}.
     * @throws PaymentException 400 if no such gateway is registered.
     */
    public PaymentGatewayPort require(String gatewayId) {
        PaymentGatewayPort gateway = byId.get(gatewayId);
        if (gateway == null) {
            throw PaymentException.validation(
                    "Unknown payment gateway '" + gatewayId + "'; registered: " + byId.keySet());
        }
        return gateway;
    }

    public Set<String> registeredGateways() {
        return byId.keySet();
    }
}
