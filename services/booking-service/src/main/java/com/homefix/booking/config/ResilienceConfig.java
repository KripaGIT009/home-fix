package com.homefix.booking.config;

import com.homefix.shared.resilience.ResilienceFactory;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the shared resilience primitives (Requirement 24) for the Booking Service. A single
 * {@link ResilienceFactory} is shared by the Service Catalog and Pricing Engine clients so each
 * downstream dependency gets its own independently-tracked circuit breaker and retry with the
 * platform-standard configuration (50% / 10-call window / 30 s open; 3 attempts / 500 ms–8 s
 * backoff).
 *
 * <p>Circuit-breaker state is bound to the Micrometer registry so it surfaces on {@code /metrics}
 * per dependency (Requirement 25.4). Binding is best-effort: when no registry is present (slice
 * tests) the factory is still provided.
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
