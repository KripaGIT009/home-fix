package com.homefix.auth.sms;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

/**
 * Unit test for the default logging SMS gateway (Requirement 1.16 fallback adapter).
 *
 * <p>The adapter never contacts a carrier and must not throw; it deliberately logs neither the
 * mobile number nor the OTP-bearing message body (PII).
 */
class LoggingSmsGatewayAdapterTest {

    private final LoggingSmsGatewayAdapter adapter = new LoggingSmsGatewayAdapter();

    @Test
    void send_acceptsMessageWithoutThrowing() {
        assertThatCode(() -> adapter.send("+919876543210", "Your HomeFix verification code is 123456."))
                .doesNotThrowAnyException();
    }
}
