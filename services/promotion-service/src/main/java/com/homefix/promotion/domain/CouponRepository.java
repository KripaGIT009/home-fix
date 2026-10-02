package com.homefix.promotion.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link Coupon}. The unique {@code code} column (stored upper-cased) backs
 * case-insensitive lookup and duplicate detection (Requirement 21.1).
 */
public interface CouponRepository extends JpaRepository<Coupon, UUID> {

    /** Looks up a coupon by its already-normalised (upper-case) code. */
    Optional<Coupon> findByCode(String code);

    boolean existsByCode(String code);

    /** Coupons newest first, bounded by {@code page}: the Admin Portal list (Requirement 19.2). */
    List<Coupon> findAllByOrderByCreatedAtDesc(Pageable page);
}
