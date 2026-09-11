package com.homefix.invoice.api;

import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.homefix.invoice.service.InvoiceException;

/**
 * Resolves the authenticated caller and enforces <em>ownership</em> of an invoice resource.
 *
 * <p>Role-based access control ({@code InvoiceRbacConfig}) answers "may this kind of caller use this
 * endpoint"; it cannot answer "may this particular caller read <em>this</em> customer's or
 * <em>this</em> provider's records". Without the check below, any authenticated CUSTOMER could pull
 * another customer's entire invoice history (amounts, bookings, signed PDF URLs) and any
 * SERVICE_PROVIDER could read a competitor's monthly earnings, simply by substituting the id in the
 * path.
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
            throw new InvoiceException(HttpStatus.UNAUTHORIZED, "INVALID_PRINCIPAL",
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
     * @throws InvoiceException 403 when an ordinary caller targets somebody else's resource
     */
    public void requireSelfOrStaff(UUID target) {
        if (isStaff()) {
            return;
        }
        if (!requireCallerId().equals(target)) {
            throw new InvoiceException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "caller may only act on their own invoices");
        }
    }

    private Authentication requireAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new InvoiceException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "no authenticated principal");
        }
        return authentication;
    }
}
