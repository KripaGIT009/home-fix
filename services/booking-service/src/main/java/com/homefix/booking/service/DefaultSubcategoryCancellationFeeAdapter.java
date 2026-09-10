package com.homefix.booking.service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Default {@link SubcategoryCancellationFeePort} that returns no per-subcategory fee, so the
 * {@link CancellationFeePolicy} applies the configured platform default. Replaced by a
 * Pricing/Catalog-backed adapter (or a mock in tests) without touching cancellation logic.
 *
 * <p>Registered as the fallback bean via {@code DomainConfig} using a
 * {@code @Bean @ConditionalOnMissingBean} factory method. It is intentionally <em>not</em> a
 * {@code @Component}: {@code @ConditionalOnMissingBean} is evaluated during component scanning
 * and is order-sensitive when placed directly on a scanned class, which caused the default to
 * be silently skipped (leaving {@link CancellationFeePolicy} without its port). Declaring it in
 * a configuration class evaluates the condition reliably against the full bean set.
 */
public class DefaultSubcategoryCancellationFeeAdapter implements SubcategoryCancellationFeePort {

    @Override
    public Optional<BigDecimal> cancellationFee(UUID subcategoryId) {
        return Optional.empty();
    }
}
