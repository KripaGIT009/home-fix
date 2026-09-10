package com.homefix.catalog.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link ServiceCategory} aggregates.
 */
public interface ServiceCategoryRepository extends JpaRepository<ServiceCategory, UUID> {

    /** Active categories only, ordered for customer-facing listings (Requirement 3.5). */
    List<ServiceCategory> findByActiveTrueOrderByDisplayOrderAsc();

    List<ServiceCategory> findAllByOrderByDisplayOrderAsc();
}
