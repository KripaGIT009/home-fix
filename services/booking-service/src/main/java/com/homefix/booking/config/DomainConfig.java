package com.homefix.booking.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.homefix.booking.domain.BookingStateMachine;
import com.homefix.booking.service.DefaultSubcategoryCancellationFeeAdapter;
import com.homefix.booking.service.SubcategoryCancellationFeePort;

/**
 * Exposes stateless domain components as Spring beans. {@link BookingStateMachine} is kept as
 * a plain domain class (no Spring annotations) so it stays reusable and unit-testable in
 * isolation; this config wires it into the container.
 */
@Configuration
public class DomainConfig {

    @Bean
    public BookingStateMachine bookingStateMachine() {
        return new BookingStateMachine();
    }

    /**
     * Fallback {@link SubcategoryCancellationFeePort} (no per-subcategory fee) used unless a
     * Pricing/Catalog-backed adapter or a test double supplies one. Declared here (rather than
     * annotating the adapter with {@code @Component + @ConditionalOnMissingBean}) so the
     * condition is evaluated reliably against the full set of beans.
     */
    @Bean
    @ConditionalOnMissingBean(SubcategoryCancellationFeePort.class)
    public SubcategoryCancellationFeePort defaultSubcategoryCancellationFeePort() {
        return new DefaultSubcategoryCancellationFeeAdapter();
    }
}
