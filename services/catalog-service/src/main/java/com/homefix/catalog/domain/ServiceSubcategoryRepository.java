package com.homefix.catalog.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link ServiceSubcategory} aggregates.
 */
public interface ServiceSubcategoryRepository extends JpaRepository<ServiceSubcategory, UUID> {

    List<ServiceSubcategory> findByCategoryId(UUID categoryId);

    List<ServiceSubcategory> findByCategoryIdAndActiveTrue(UUID categoryId);
}
