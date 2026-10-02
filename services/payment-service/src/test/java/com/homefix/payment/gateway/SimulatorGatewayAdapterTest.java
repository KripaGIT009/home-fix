package com.homefix.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.homefix.payment.service.SignedCallbackPayload;

/**
 * The local simulator gateway (contract item 5): it must not exist unless explicitly enabled, and
 * the callbacks it signs must pass the same verification and parsing as a real gateway's.
 */
class SimulatorGatewayAdapterTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(SimulatorGatewayAdapter.class);

    @Test
    void absentWhenThePropertyIsUnset() {
        context.run(ctx -> assertThat(ctx).doesNotHaveBean(SimulatorGatewayAdapter.class));
    }

    @Test
    void absentWhenDisabled() {
        context.withPropertyValues("homefix.payment.gateways.simulator.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(SimulatorGatewayAdapter.class));
    }

    @Test
    void presentOnlyWhenExplicitlyEnabled() {
        context.withPropertyValues("homefix.payment.gateways.simulator.enabled=true",
                        "homefix.payment.gateways.simulator.secret=configured-secret")
                .run(ctx -> assertThat(ctx).hasSingleBean(SimulatorGatewayAdapter.class));
    }

    @Test
    void enabledWithoutASecret_fallsBackToTheDevSecret() {
        context.withPropertyValues("homefix.payment.gateways.simulator.enabled=true")
                .run(ctx -> {
                    SimulatorGatewayAdapter simulator = ctx.getBean(SimulatorGatewayAdapter.class);
                    SelfSettlingGatewayPort.SignedSettlement s =
                            simulator.settlementFor(UUID.randomUUID(), new BigDecimal("10.00"));
                    assertThat(HmacSignatures.verify(SimulatorGatewayAdapter.DEV_DEFAULT_SECRET,
                            s.payload(), s.signature())).isTrue();
                });
    }

    @Test
    void settlement_isASignedSuccessCallbackInTheDocumentedFormat() {
        SimulatorGatewayAdapter simulator = new SimulatorGatewayAdapter("sim-secret");
        UUID transactionId = UUID.randomUUID();

        SelfSettlingGatewayPort.SignedSettlement settlement =
                simulator.settlementFor(transactionId, new BigDecimal("499.00"));

        assertThat(simulator.verifyCallbackSignature(settlement.payload(), settlement.signature())).isTrue();
        assertThat(HmacSignatures.verify("another-secret", settlement.payload(), settlement.signature())).isFalse();
        SignedCallbackPayload parsed = SignedCallbackPayload.parse(settlement.payload());
        assertThat(parsed.transactionId()).isEqualTo(transactionId);
        assertThat(parsed.gatewayId()).isEqualTo(SimulatorGatewayAdapter.GATEWAY_ID);
        assertThat(parsed.outcome()).isEqualTo(SignedCallbackPayload.Outcome.SUCCEEDED);
        assertThat(parsed.amount()).isEqualByComparingTo("499.00");
        assertThat(parsed.eventId()).startsWith("sim_evt_");
        assertThat(Duration.between(parsed.timestamp(), Instant.now()).abs()).isLessThan(Duration.ofMinutes(1));
    }

    @Test
    void eachSettlementHasItsOwnEventId() {
        SimulatorGatewayAdapter simulator = new SimulatorGatewayAdapter("sim-secret");
        UUID transactionId = UUID.randomUUID();

        String first = SignedCallbackPayload.parse(
                simulator.settlementFor(transactionId, BigDecimal.TEN).payload()).eventId();
        String second = SignedCallbackPayload.parse(
                simulator.settlementFor(transactionId, BigDecimal.TEN).payload()).eventId();

        assertThat(first).isNotEqualTo(second);
    }
}
