package com.homefix.booking.api;

import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.homefix.booking.service.Actor;
import com.homefix.booking.service.BookingException;

/**
 * Who is calling, for the booking read endpoints' ownership decisions — the same component the
 * customer, invoice and payment services use, with the same staff set.
 *
 * <p>{@code RbacEnforcementFilter} only answers "may this role use the endpoint at all?". Whether
 * the caller may see <em>this</em> booking depends on the booking's customer and provider, which
 * only the service knows once it has loaded it; this component supplies the two facts that
 * decision needs: the caller's id (the JWT subject) and whether they hold a staff role.
 */
@Component
public class CallerIdentity {

    /** Roles that may legitimately read another user's booking. */
    private static final Set<String> STAFF_AUTHORITIES = Set.of(
            "ROLE_ADMIN", "ROLE_SUPER_ADMIN", "ROLE_FINANCE_ADMIN",
            "ROLE_SUPPORT_AGENT", "ROLE_DISPATCHER");

    /** @return the authenticated caller's user id (the JWT subject). */
    public UUID requireCallerId() {
        Authentication authentication = requireAuthentication();
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            throw new BookingException(HttpStatus.UNAUTHORIZED, "INVALID_PRINCIPAL",
                    "authenticated principal is not a valid user ID");
        }
    }

    /** @return true when the caller holds a staff role and may read any booking. */
    public boolean isStaff() {
        return requireAuthentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(STAFF_AUTHORITIES::contains);
    }

    /**
     * The caller as a staff {@link Actor}, for the admin commands: the audit trail records the
     * staff role they acted under, and {@code BookingAccess} admits a staff role to any booking.
     * The role is taken from the staff authorities only — a staff user who also books services
     * must not be recorded (or authorized) as a customer because that authority came first.
     *
     * @throws BookingException 403 when the caller holds no staff role; {@code BookingRbacConfig}
     *         keeps such callers off the admin paths, so this only guards a misconfiguration
     */
    public Actor requireStaffActor() {
        UUID id = requireCallerId();
        String role = requireAuthentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(STAFF_AUTHORITIES::contains)
                .sorted()
                .findFirst()
                .map(a -> a.substring("ROLE_".length()))
                .orElseThrow(() -> new BookingException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                        "a staff role is required"));
        return Actor.user(id, role);
    }

    private Authentication requireAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new BookingException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "no authenticated principal");
        }
        return authentication;
    }
}
