package com.homefix.pricing.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to {@code pricing.pricing_parameters}, keyed by subcategory id. */
public interface PricingParametersRepository extends JpaRepository<PricingParametersEntity, UUID> {
}
