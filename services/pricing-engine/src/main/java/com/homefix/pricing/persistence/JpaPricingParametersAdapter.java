package com.homefix.pricing.persistence;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.homefix.pricing.config.PricingParametersPort;
import com.homefix.pricing.domain.PricingParameters;

/**
 * Durable {@link PricingParametersPort}: the Admin-configured parameters live in
 * {@code pricing.pricing_parameters} (Requirement 6.11).
 *
 * <p>This replaces the in-memory store the service used to run on, which lost every parameter
 * on restart, so every estimate returned {@code PRICING_PARAMETERS_NOT_FOUND} and every booking
 * failed until {@code docker/seed-pricing.sh} was rerun (CODEBASE_REVIEW 8.3). Parameters are now
 * seeded once per database. The read-through {@code PricingConfigCachePort} (Redis or in-memory)
 * still sits in front of this adapter, so the database is consulted at most once per subcategory
 * per cache TTL.
 */
@Component
public class JpaPricingParametersAdapter implements PricingParametersPort {

    private final PricingParametersRepository repository;
    private final Clock clock;

    @Autowired
    public JpaPricingParametersAdapter(PricingParametersRepository repository) {
        this(repository, Clock.systemUTC());
    }

    /** Test constructor allowing a controllable clock for the audit timestamps. */
    JpaPricingParametersAdapter(PricingParametersRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PricingParameters> findBySubcategoryId(UUID subcategoryId) {
        return repository.findById(subcategoryId).map(PricingParametersEntity::toDomain);
    }

    /** Newest-updated first; the subcategory id breaks ties so the order is stable. */
    @Override
    @Transactional(readOnly = true)
    public List<PricingParameters> findAll(int limit) {
        Sort newestFirst = Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.asc("subcategoryId"));
        return repository.findAll(PageRequest.of(0, limit, newestFirst)).stream()
                .map(PricingParametersEntity::toDomain)
                .toList();
    }

    /**
     * Inserts or replaces the subcategory's row. The read and the write share one transaction so
     * an update keeps the row's original {@code created_at}.
     */
    @Override
    @Transactional
    public PricingParameters save(PricingParameters parameters) {
        Instant now = Instant.now(clock);
        PricingParametersEntity entity = repository.findById(parameters.subcategoryId())
                .orElseGet(() -> PricingParametersEntity.newFor(parameters.subcategoryId(), now));
        entity.replaceWith(parameters, now);
        return repository.save(entity).toDomain();
    }
}
