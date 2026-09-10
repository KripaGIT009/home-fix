package com.homefix.promotion.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link CouponUsage}. The unique {@code (couponId, userId)} constraint backs the
 * per-user usage counter (Requirement 21.3, 21.5).
 */
public interface CouponUsageRepository extends JpaRepository<CouponUsage, UUID> {

    Optional<CouponUsage> findByCouponIdAndUserId(UUID couponId, UUID userId);
}
