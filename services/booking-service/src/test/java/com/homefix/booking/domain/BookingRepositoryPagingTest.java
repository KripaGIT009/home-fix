package com.homefix.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import com.homefix.booking.support.Bookings;

/**
 * Runs {@link BookingRepository#findByCustomerIdOrderByCreatedAtDescIdDesc} against a real
 * (H2, PostgreSQL-mode) database, so the derived query's name, its ordering and Spring Data's
 * page arithmetic are checked by the persistence stack rather than by a fake's reading of them.
 *
 * <p>The schema is generated from the entities, as in the integration profile; Flyway stays off
 * (test {@code config/application.properties}) because the migrations are PostgreSQL DDL. The
 * shared outbox entities live in the {@code outbox} schema, which H2 creates on connect.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:booking_paging;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;"
                + "INIT=CREATE SCHEMA IF NOT EXISTS outbox",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.default_schema="
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class BookingRepositoryPagingTest {

    private static final Instant T0 = Instant.parse("2026-09-01T08:00:00Z");

    @Autowired
    private BookingRepository repository;

    private Booking place(UUID customer, String reference, long minutes) {
        return repository.save(Bookings.placed(customer, UUID.randomUUID(), reference,
                T0.plus(Duration.ofMinutes(minutes)), null));
    }

    @Test
    void pagesOneCustomersBookingsNewestFirst() {
        UUID customer = UUID.randomUUID();
        place(customer, "HFX-2", 2);
        place(customer, "HFX-1", 1);
        place(customer, "HFX-3", 3);
        place(UUID.randomUUID(), "HFX-OTHER", 10);

        Page<Booking> first = repository.findByCustomerIdOrderByCreatedAtDescIdDesc(customer, PageRequest.of(0, 2));
        Page<Booking> second = repository.findByCustomerIdOrderByCreatedAtDescIdDesc(customer, PageRequest.of(1, 2));

        assertThat(first.getContent()).extracting(Booking::getReference).containsExactly("HFX-3", "HFX-2");
        assertThat(second.getContent()).extracting(Booking::getReference).containsExactly("HFX-1");
        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getTotalPages()).isEqualTo(2);
    }

    @Test
    void adminSearchMatchesReferenceFragmentsCaseInsensitivelyAndLiterally() {
        place(UUID.randomUUID(), "HFX-20261001-ABC123", 1);
        place(UUID.randomUUID(), "HFX-20261001-ABX999", 3);
        Booking cancelled = place(UUID.randomUUID(), "HFX-20261001-QQQ000", 2);
        cancelled.applyStatus(BookingStatus.CANCELLED);
        repository.save(cancelled);
        var all = java.util.EnumSet.allOf(BookingStatus.class);

        assertThat(repository.findByReferenceContainingIgnoreCaseAndStatusInOrderByCreatedAtDescIdDesc(
                        "hfx-2026", all, PageRequest.of(0, 10)))
                .extracting(Booking::getReference)
                .containsExactly("HFX-20261001-ABX999", "HFX-20261001-QQQ000", "HFX-20261001-ABC123");
        assertThat(repository.findByReferenceContainingIgnoreCaseAndStatusInOrderByCreatedAtDescIdDesc(
                        "", java.util.EnumSet.of(BookingStatus.CANCELLED), PageRequest.of(0, 10)))
                .extracting(Booking::getReference)
                .containsExactly("HFX-20261001-QQQ000");
        // "_" is a LIKE wildcard; the search must treat it as the literal character.
        assertThat(repository.findByReferenceContainingIgnoreCaseAndStatusInOrderByCreatedAtDescIdDesc(
                        "AB_", all, PageRequest.of(0, 10)))
                .isEmpty();
        assertThat(repository.findByReferenceContainingIgnoreCaseAndStatusInOrderByCreatedAtDescIdDesc(
                        "hfx", all, PageRequest.of(0, 2)))
                .hasSize(2);
    }

    @Test
    void sameInstantBookingsPageWithoutRepeatsOrGaps() {
        UUID customer = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            place(customer, "HFX-TIE-" + i, 0);
        }

        var seen = new java.util.ArrayList<String>();
        for (int page = 0; page < 3; page++) {
            repository.findByCustomerIdOrderByCreatedAtDescIdDesc(customer, PageRequest.of(page, 2))
                    .forEach(b -> seen.add(b.getReference()));
        }

        assertThat(seen).hasSize(5).doesNotHaveDuplicates();
    }
}
