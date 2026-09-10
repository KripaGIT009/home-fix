package com.homefix.promotion.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link Coupon}. The unique {@code code} column (stored upper-cased) backs
 * case-insensitive lookup and duplicate detection (Requirement 21.1).
 */
public interface CouponRepository extends JpaRepository<Coupon, UUID> {

    /** Looks up a coupon by its already-normalised (upper-case) code. */
    Optional<Coupon> findByCode(String code);

    boolean existsByCode(String code);
}
