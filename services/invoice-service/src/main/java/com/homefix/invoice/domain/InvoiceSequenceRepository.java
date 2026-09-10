package com.homefix.invoice.domain;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for the per-month {@link InvoiceSequence} counters.
 *
 * <p>{@link #lockByPeriod(String)} takes a pessimistic write lock on the period row so a claim +
 * increment is serialized across concurrent transactions; combined with the unique constraint on
 * {@code invoice.invoice_number}, this guarantees no two invoices in a month share a sequence
 * number (Requirement 13.4, Property 14).
 */
public interface InvoiceSequenceRepository extends JpaRepository<InvoiceSequence, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from InvoiceSequence s where s.period = :period")
    Optional<InvoiceSequence> lockByPeriod(@Param("period") String period);
}
