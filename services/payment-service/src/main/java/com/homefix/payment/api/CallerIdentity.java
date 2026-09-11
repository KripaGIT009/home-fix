package com.homefix.payment.api;

import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.homefix.payment.service.PaymentException;

/**
 * Resolves the authenticated caller and enforces <em>ownership</em> of a payment resource.
 *
 * <p>Role-based access control ({@code PaymentRbacConfig}) answers "may this kind of caller use
 * this endpoint"; it cannot answer "may this particular caller touch <em>this</em> record". Without
 * the check below, any authenticated CUSTOMER could read another customer's transaction — including
 * its amount, booking, provider and gateway reference — simply by guessing or harvesting a
 * transaction id, and could open a payment in somebody else's name.
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
            throw new PaymentException(HttpStatus.UNAUTHORIZED, "INVALID_PRINCIPAL",
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
     * @throws PaymentException 403 when an ordinary caller targets somebody else's resource
     */
    public void requireSelfOrStaff(UUID target) {
        if (isStaff()) {
            return;
        }
        if (!requireCallerId().equals(target)) {
            throw new PaymentException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "caller may only act on their own payments");
        }
    }

    private Authentication requireAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new PaymentException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "no authenticated principal");
        }
        return authentication;
    }
}
