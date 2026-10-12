package com.homefix.location.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.homefix.location.booking.BookingParticipants;
import com.homefix.location.booking.BookingParticipantsPort;

/**
 * Tests for {@link LocationAccessPolicy}: a booking's provider position is visible only to its
 * customer, its assigned provider and staff, and only the assigned provider may post one. Anyone
 * else, and any unknown booking, gets the same 404; an unreachable Booking Service is not a pass.
 */
class LocationAccessPolicyTest {

    private static final List<GrantedAuthority> CUSTOMER = List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    private static final List<GrantedAuthority> PROVIDER =
            List.of(new SimpleGrantedAuthority("ROLE_SERVICE_PROVIDER"));
    private static final List<GrantedAuthority> SUPPORT =
            List.of(new SimpleGrantedAuthority("ROLE_SUPPORT_AGENT"));

    private final UUID bookingId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final UUID providerId = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();

    private final Map<UUID, BookingParticipants> bookings = new HashMap<>();
    private final AtomicInteger lookups = new AtomicInteger();
    private final BookingParticipantsPort port = id -> {
        lookups.incrementAndGet();
        return Optional.ofNullable(bookings.get(id));
    };
    private final LocationAccessPolicy policy = new LocationAccessPolicy(port);

    private void givenBooking(UUID assignedProvider) {
        bookings.put(bookingId, new BookingParticipants(bookingId, customerId, assignedProvider, "PROVIDER_ACCEPTED"));
    }

    @Test
    void customerOfTheBooking_mayRead() {
        givenBooking(providerId);

        assertThatCode(() -> policy.requireReadAccess(bookingId, customerId, CUSTOMER)).doesNotThrowAnyException();
    }

    @Test
    void assignedProvider_mayRead() {
        givenBooking(providerId);

        assertThatCode(() -> policy.requireReadAccess(bookingId, providerId, PROVIDER)).doesNotThrowAnyException();
    }

    @Test
    void anotherCustomerOrProvider_cannotRead_andGetsTheSameAnswerAsForAnUnknownBooking() {
        givenBooking(providerId);

        assertNotFound(() -> policy.requireReadAccess(bookingId, stranger, CUSTOMER));
        assertNotFound(() -> policy.requireReadAccess(bookingId, stranger, PROVIDER));
        assertNotFound(() -> policy.requireReadAccess(UUID.randomUUID(), customerId, CUSTOMER));
    }

    @Test
    void staff_mayReadAnyBooking_withoutALookup() {
        assertThatCode(() -> policy.requireReadAccess(bookingId, stranger, SUPPORT)).doesNotThrowAnyException();
        assertThat(lookups).hasValue(0);
    }

    @Test
    void onlyTheAssignedProvider_mayPost() {
        givenBooking(providerId);

        assertThatCode(() -> policy.requireAssignedProvider(bookingId, providerId)).doesNotThrowAnyException();
        assertNotFound(() -> policy.requireAssignedProvider(bookingId, stranger));
        // The customer is a participant but never the source of a provider position.
        assertNotFound(() -> policy.requireAssignedProvider(bookingId, customerId));
    }

    @Test
    void noProviderAssignedYet_nobodyMayPost() {
        givenBooking(null);

        assertNotFound(() -> policy.requireAssignedProvider(bookingId, providerId));
    }

    @Test
    void bookingServiceUnavailable_refusesRatherThanAllowing() {
        BookingParticipantsPort down = id -> {
            throw LocationException.bookingLookupUnavailable("down");
        };
        LocationAccessPolicy failing = new LocationAccessPolicy(down);

        assertThatThrownBy(() -> failing.requireReadAccess(bookingId, customerId, CUSTOMER))
                .isInstanceOfSatisfying(LocationException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> failing.requireAssignedProvider(bookingId, providerId))
                .isInstanceOf(LocationException.class);
    }

    private static void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(LocationException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getErrorCode()).isEqualTo("LOCATION_BOOKING_NOT_FOUND");
                });
    }
}
