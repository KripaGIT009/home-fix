package com.homefix.promotion.api;

import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.homefix.promotion.service.CouponException;

/**
 * Resolves the authenticated caller and asserts ownership of the {@code userId} carried in coupon
 * request bodies.
 *
 * <p>The shared {@code RbacEnforcementFilter} can only decide <em>role</em>: it admits any
 * {@code CUSTOMER} to {@code POST /coupons/validate}, {@code /redeem} and {@code /cancel}. Those
 * requests name the customer whose allowance moves in the body, not the path, so without a further
 * check one customer could spend, probe, or restore another customer's per-user coupon limit
 * (Requirement 21.2, 21.3, 21.5). This helper supplies that missing dimension, and reuses
 * {@link CouponException} so failures leave through the existing {@code GlobalExceptionHandler} and
 * the shared {@code ErrorResponseDto} envelope.
 */
@Component
public class CallerIdentity {

    /** Roles that may legitimately act on another user's resource. */
    private static final Set<String> STAFF_AUTHORITIES = Set.of(
            "ROLE_ADMIN", "ROLE_SUPER_ADMIN", "ROLE_FINANCE_ADMIN",
            "ROLE_SUPPORT_AGENT", "ROLE_DISPATCHER");

    /** @return the authenticated caller's user id (the JWT subject). */
    public UUID requireCallerId() {
        Authentication authentication = requireAuthentication();
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            throw new CouponException(HttpStatus.UNAUTHORIZED, "INVALID_PRINCIPAL",
                    "authenticated principal is not a valid user ID");
        }
    }

    /** @return true when the caller holds a staff role and may act on behalf of others. */
    public boolean isStaff() {
        return requireAuthentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(STAFF_AUTHORITIES::contains);
    }

    /**
     * Asserts the caller is {@code target} themselves, or holds a staff role.
     *
     * @throws CouponException 403 when an ordinary caller targets somebody else's coupon allowance
     */
    public void requireSelfOrStaff(UUID target) {
        if (isStaff()) {
            return;
        }
        if (!requireCallerId().equals(target)) {
            throw new CouponException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "caller may only act on their own coupon usage");
        }
    }

    private Authentication requireAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new CouponException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "no authenticated principal");
        }
        return authentication;
    }
}
