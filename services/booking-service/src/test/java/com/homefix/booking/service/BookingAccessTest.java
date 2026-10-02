package com.homefix.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingStatus;
import com.homefix.booking.support.Bookings;
import com.homefix.booking.support.InMemoryBookingRepository;

/**
 * Ownership rules for booking commands: before these existed any signed-in user who knew a
 * reference could start, complete or cancel someone else's booking.
 */
class BookingAccessTest {

    private static final UUID PROVIDER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID STRANGER = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private InMemoryBookingRepository repository;
    private Booking booking;

    @BeforeEach
    void setUp() {
        repository = new InMemoryBookingRepository();
        booking = Bookings.inState(BookingStatus.PROVIDER_ACCEPTED);
        booking.setProviderId(PROVIDER);
        repository.save(booking);
    }

    private Actor customer() {
        return Actor.user(booking.getCustomerId(), "CUSTOMER");
    }

    private static Actor provider() {
        return Actor.user(PROVIDER, "SERVICE_PROVIDER");
    }

    @Test
    void findsTheBookingByUuidOrByReference() {
        assertThat(BookingAccess.requireForProvider(repository, booking.getId().toString(), provider()))
                .isSameAs(booking);
        assertThat(BookingAccess.requireForProvider(repository, booking.getReference(), provider()))
                .isSameAs(booking);
    }

    @Test
    void providerCommandsBelongToTheAssignedProvider() {
        assertThatNotFound(() -> BookingAccess.requireForProvider(repository, booking.getReference(), customer()));
        assertThatNotFound(() -> BookingAccess.requireForProvider(repository, booking.getReference(),
                Actor.user(STRANGER, "SERVICE_PROVIDER")));
    }

    @Test
    void customerCommandsBelongToTheCustomer() {
        assertThat(BookingAccess.requireForCustomer(repository, booking.getReference(), customer()))
                .isSameAs(booking);
        assertThatNotFound(() -> BookingAccess.requireForCustomer(repository, booking.getReference(), provider()));
        assertThatNotFound(() -> BookingAccess.requireForCustomer(repository, booking.getReference(),
                Actor.user(STRANGER, "CUSTOMER")));
    }

    @Test
    void eitherParticipantMayCancelButNobodyElse() {
        assertThat(BookingAccess.requireForParticipant(repository, booking.getReference(), customer()))
                .isSameAs(booking);
        assertThat(BookingAccess.requireForParticipant(repository, booking.getReference(), provider()))
                .isSameAs(booking);
        assertThatNotFound(() -> BookingAccess.requireForParticipant(repository, booking.getReference(),
                Actor.user(STRANGER, "CUSTOMER")));
    }

    @Test
    void anUnassignedBookingBelongsToNoProvider() {
        Booking searching = Booking.create("HFX-20261002-UNASGN", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), false, null, new BigDecimal("100.00"));
        searching.applyStatus(BookingStatus.SEARCHING_PROVIDER);
        repository.save(searching);

        assertThatNotFound(() -> BookingAccess.requireForProvider(repository, searching.getId().toString(), provider()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "SUPER_ADMIN", "FINANCE_ADMIN", "SUPPORT_AGENT", "DISPATCHER"})
    void staffMayActOnAnyBooking(String role) {
        Actor staff = Actor.user(STRANGER, role);

        assertThat(BookingAccess.requireForProvider(repository, booking.getReference(), staff)).isSameAs(booking);
        assertThat(BookingAccess.requireForCustomer(repository, booking.getReference(), staff)).isSameAs(booking);
    }

    @Test
    void theSystemMayActOnAnyBooking() {
        assertThat(BookingAccess.requireForProvider(repository, booking.getReference(), Actor.system()))
                .isSameAs(booking);
    }

    private static void assertThatNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(BookingException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
