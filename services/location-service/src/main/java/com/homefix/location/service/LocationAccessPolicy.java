package com.homefix.location.service;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import com.homefix.location.booking.BookingParticipants;
import com.homefix.location.booking.BookingParticipantsPort;

/**
 * Ownership checks for a Booking's location feed.
 *
 * <p>Role-based access control ({@code LocationRbacConfig}) answers "may this kind of caller use the
 * endpoint"; it cannot answer "may this caller see <em>this</em> booking". Without the checks below
 * any signed-in customer or provider holding a booking id could follow that booking's provider on a
 * map, and any provider could post positions into another provider's booking.
 *
 * <ul>
 *   <li><strong>Reading</strong> (snapshot or stream) is for the booking's customer, its assigned
 *       provider, and the staff roles that handle disputes and dispatch, who need no lookup.</li>
 *   <li><strong>Posting</strong> is for the booking's assigned provider only.</li>
 * </ul>
 *
 * <p>An unknown booking and somebody else's booking get the same 404, so ids cannot be probed. When
 * the Booking Service cannot be asked the port fails closed with 503.
 */
@Component
public class LocationAccessPolicy {

    /** Staff roles that may read any booking's location (a subset of the RBAC readers). */
    static final Set<String> STAFF_AUTHORITIES = Set.of(
            "ROLE_ADMIN", "ROLE_SUPER_ADMIN", "ROLE_SUPPORT_AGENT", "ROLE_DISPATCHER");

    private final BookingParticipantsPort bookings;

    public LocationAccessPolicy(BookingParticipantsPort bookings) {
        this.bookings = bookings;
    }

    /**
     * Allows a read of {@code bookingId}'s location by its customer, its assigned provider, or staff.
     *
     * @throws LocationException 404 {@code LOCATION_BOOKING_NOT_FOUND} when the booking is unknown or
     *                           not the caller's; 503 when the Booking Service cannot be asked
     */
    public void requireReadAccess(UUID bookingId, UUID callerId,
                                  Collection<? extends GrantedAuthority> authorities) {
        if (isStaff(authorities)) {
            return;
        }
        BookingParticipants participants = participants(bookingId);
        if (!participants.isCustomer(callerId) && !participants.isAssignedProvider(callerId)) {
            throw notFound(bookingId);
        }
    }

    /**
     * Allows {@code providerId} to post a position for {@code bookingId} only when assigned to it.
     *
     * @throws LocationException 404 {@code LOCATION_BOOKING_NOT_FOUND} when the booking is unknown or
     *                           assigned to someone else; 503 when the Booking Service cannot be asked
     */
    public void requireAssignedProvider(UUID bookingId, UUID providerId) {
        if (!participants(bookingId).isAssignedProvider(providerId)) {
            throw notFound(bookingId);
        }
    }

    private BookingParticipants participants(UUID bookingId) {
        return bookings.participants(bookingId).orElseThrow(() -> notFound(bookingId));
    }

    private static boolean isStaff(Collection<? extends GrantedAuthority> authorities) {
        return authorities != null && authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(STAFF_AUTHORITIES::contains);
    }

    private static LocationException notFound(UUID bookingId) {
        return LocationException.bookingNotFound("Booking " + bookingId + " not found");
    }
}
