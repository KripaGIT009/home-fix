package com.homefix.dispatch.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the bulkhead exposes two distinct, non-shared pools so emergency dispatch is isolated
 * from scheduled dispatch (Requirement 24.5).
 */
class BulkheadConfigTest {

    @Test
    void emergencyAndScheduledExecutorsAreSeparateInstances() {
        BulkheadConfig config = new BulkheadConfig();
        DispatchBulkheadProperties props = new DispatchBulkheadProperties();

        ThreadPoolTaskExecutor emergency = config.emergencyDispatchExecutor(props);
        ThreadPoolTaskExecutor scheduled = config.scheduledDispatchExecutor(props);

        assertThat(emergency).isNotSameAs(scheduled);
        // Non-shared underlying thread pools.
        assertThat(emergency.getThreadPoolExecutor())
                .isNotSameAs(scheduled.getThreadPoolExecutor());
        assertThat(emergency.getThreadNamePrefix()).isEqualTo("emergency-dispatch-");
        assertThat(scheduled.getThreadNamePrefix()).isEqualTo("scheduled-dispatch-");
    }

    @Test
    void poolsAreSizedFromProperties() {
        BulkheadConfig config = new BulkheadConfig();
        DispatchBulkheadProperties props = new DispatchBulkheadProperties();
        props.setEmergencyCoreThreads(4);
        props.setEmergencyMaxThreads(9);

        ThreadPoolTaskExecutor emergency = config.emergencyDispatchExecutor(props);

        assertThat(emergency.getCorePoolSize()).isEqualTo(4);
        assertThat(emergency.getMaxPoolSize()).isEqualTo(9);
    }
}
