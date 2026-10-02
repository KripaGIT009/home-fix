package com.homefix.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import com.homefix.payment.gateway.RazorpayGatewayAdapter;

/**
 * Runs {@link PaymentTransactionRepository#searchForAdmin} against a real (H2, PostgreSQL-mode)
 * database, so the JPQL — the UUID-to-text casts, the {@code !} LIKE escape and the ordering — is
 * checked by the persistence stack rather than by the in-memory fake's reading of it.
 *
 * <p>The schema is generated from the entities; Flyway stays off (test
 * {@code config/application.properties}) because the migrations are PostgreSQL DDL. The shared
 * outbox entities live in the {@code outbox} schema, which H2 creates on connect.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:payment_admin_search;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;"
                + "INIT=CREATE SCHEMA IF NOT EXISTS outbox",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.default_schema="
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PaymentTransactionRepositoryAdminSearchTest {

    private static final Instant T0 = Instant.parse("2026-09-01T08:00:00Z");

    @Autowired
    private PaymentTransactionRepository repository;

    private PaymentTransaction save(UUID bookingId, String gatewayReference, long minutes) {
        PaymentTransaction tx = PaymentTransaction.initiate("key-" + UUID.randomUUID(), UUID.randomUUID(),
                bookingId, UUID.randomUUID(), new BigDecimal("100.00"), new BigDecimal("20.00"),
                PaymentMethod.UPI, RazorpayGatewayAdapter.GATEWAY_ID, null);
        tx.setGatewayReference(gatewayReference);
        ReflectionTestUtils.setField(tx, "createdAt", T0.plusSeconds(minutes * 60));
        return repository.save(tx);
    }

    @Test
    void matchesIdsAndGatewayReferenceSubstringsNewestFirst() {
        UUID booking = UUID.fromString("abcdef12-0000-4000-8000-000000000001");
        PaymentTransaction byBooking = save(booking, null, 1);
        PaymentTransaction byReference = save(UUID.randomUUID(), "pay_ABC_123", 3);
        PaymentTransaction other = save(UUID.randomUUID(), "pay_zzz", 2);
        PageRequest page = PageRequest.of(0, 10);

        assertThat(repository.searchForAdmin("%", page))
                .extracting(PaymentTransaction::getId)
                .containsExactly(byReference.getId(), other.getId(), byBooking.getId());
        // Booking id prefix, matched in its lower-case text form.
        assertThat(repository.searchForAdmin("%abcdef12%", page))
                .extracting(PaymentTransaction::getId)
                .containsExactly(byBooking.getId());
        // Transaction id fragment.
        String idFragment = other.getId().toString().substring(9, 18);
        assertThat(repository.searchForAdmin("%" + idFragment + "%", page))
                .extracting(PaymentTransaction::getId)
                .contains(other.getId());
        // Gateway reference, case-insensitive, with "_" escaped so it is literal.
        assertThat(repository.searchForAdmin("%c!_12%", page))
                .extracting(PaymentTransaction::getId)
                .containsExactly(byReference.getId());
        assertThat(repository.searchForAdmin("%pay!_%", page)).hasSize(2);
        assertThat(repository.searchForAdmin("%", PageRequest.of(0, 2))).hasSize(2);
    }
}
