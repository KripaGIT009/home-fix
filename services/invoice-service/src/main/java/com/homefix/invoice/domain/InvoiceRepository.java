package com.homefix.invoice.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link Invoice}. Beyond CRUD, exposes the queries backing customer invoice
 * history (Requirement 13.5) and provider monthly earnings statements (Requirement 13.6).
 */
public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    /** Used to short-circuit duplicate PaymentCompleted deliveries at the business layer. */
    Optional<Invoice> findByPaymentId(UUID paymentId);

    boolean existsByPaymentId(UUID paymentId);

    /**
     * Customer invoice history, newest first, limited to invoices generated on/after {@code since}
     * (the 24-month retention floor) (Requirement 13.5).
     */
    List<Invoice> findByCustomerIdAndGeneratedAtGreaterThanEqualOrderByGeneratedAtDesc(
            UUID customerId, Instant since, Pageable pageable);

    /** Provider invoices within a half-open [from, to) window, backing monthly statements. */
    List<Invoice> findByProviderIdAndGeneratedAtGreaterThanEqualAndGeneratedAtLessThanOrderByGeneratedAtDesc(
            UUID providerId, Instant from, Instant to);
}
