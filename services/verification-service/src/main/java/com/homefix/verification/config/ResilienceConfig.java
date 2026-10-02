package com.homefix.verification.config;

import com.homefix.shared.resilience.ResilienceFactory;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the shared resilience primitives (Requirement 24) for the Verification Service. Its only
 * outbound HTTP dependency today is the Provider Service directory lookup behind the Admin review
 * queue; a single {@link ResilienceFactory} gives each downstream dependency its own
 * independently-tracked circuit breaker and retry with the platform-standard configuration.
 *
 * <p>Mirrors the Provider Service's equivalent configuration: the factory is not auto-configured
 * by the shared module, so each service that calls out over HTTP declares it.
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
