package com.homefix.booking.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PricingUnavailableException} (Requirement 7.4): it carries the message
 * and, when supplied, the underlying cause.
 */
class PricingUnavailableExceptionTest {

    @Test
    void carriesMessage() {
        PricingUnavailableException ex = new PricingUnavailableException("down");

        assertThat(ex.getMessage()).isEqualTo("down");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void carriesMessageAndCause() {
        Throwable cause = new IllegalStateException("timeout");
        PricingUnavailableException ex = new PricingUnavailableException("down", cause);

        assertThat(ex.getMessage()).isEqualTo("down");
        assertThat(ex.getCause()).isSameAs(cause);
    }
}
