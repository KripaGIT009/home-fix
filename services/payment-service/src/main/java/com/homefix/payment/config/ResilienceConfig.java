package com.homefix.payment.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.homefix.shared.resilience.ResilienceFactory;

import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Wires the shared resilience primitives (Requirement 24) for the Payment Service, whose one
 * synchronous outbound HTTP dependency is the Booking Service ({@code HttpBookingClientAdapter}).
 * The factory is not auto-configured by the shared module, so each service that calls out over
 * HTTP declares it, as the Booking Service and Provider Service do.
 *
 * <p>Circuit-breaker state is bound to the Micrometer registry so it surfaces on {@code /metrics}
 * per dependency (Requirement 25.4); binding is skipped when no registry is present.
 */
@Configuration
public class ResilienceConfig {

    @Bean
    public ResilienceFactory resilienceFactory(ObjectProvider<MeterRegistry> meterRegistry) {
        ResilienceFactory factory = new ResilienceFactory();
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry != null) {
            TaggedCircuitBreakerMetrics
                    .ofCircuitBreakerRegistry(factory.circuitBreakerRegistry())
                    .bindTo(registry);
        }
        return factory;
    }
}
