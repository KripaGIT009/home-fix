package com.homefix.invoice.domain;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assigns invoice numbers in the format {@code INV-YYYY-MM-NNNNNN} using a per-month atomic
 * sequence, guaranteeing global uniqueness (Requirement 13.4, Property 14).
 *
 * <ul>
 *   <li>{@code YYYY} — four-digit year (UTC).</li>
 *   <li>{@code MM} — zero-padded month.</li>
 *   <li>{@code NNNNNN} — zero-padded, monotonically increasing per-month sequence, starting at 1.</li>
 * </ul>
 *
 * <p>The sequence row for the period is locked with a pessimistic write lock while its value is
 * claimed and advanced, so concurrent generation within the same month cannot hand out a duplicate
 * number. The {@code invoice.invoice_number} unique constraint is the final backstop.
 */
@Component
public class InvoiceNumberGenerator {

    /** Canonical format all invoice numbers must match (Property 14). */
    public static final Pattern FORMAT = Pattern.compile("^INV-\\d{4}-\\d{2}-\\d{6}$");

    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC);

    private final InvoiceSequenceRepository sequenceRepository;

    public InvoiceNumberGenerator(InvoiceSequenceRepository sequenceRepository) {
        this.sequenceRepository = sequenceRepository;
    }

    /**
     * Generates the next invoice number for the month containing {@code at}.
     *
     * <p>Runs in its own transaction (REQUIRES_NEW) so the sequence advance commits independently
     * of the surrounding invoice-generation flow; a downstream failure (e.g. PDF or S3) therefore
     * never rolls back an already-claimed number.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public String nextInvoiceNumber(Instant at) {
        String period = PERIOD.format(at);
        InvoiceSequence sequence = sequenceRepository.lockByPeriod(period)
                .orElseGet(() -> insertNewPeriod(period));
        long value = sequence.claim();
        sequenceRepository.save(sequence);
        return format(period, value);
    }

    private InvoiceSequence insertNewPeriod(String period) {
        try {
            return sequenceRepository.saveAndFlush(InvoiceSequence.startingAt(period));
        } catch (org.springframework.dao.DataIntegrityViolationException raceLost) {
            // A concurrent transaction inserted the row first; re-read it under the lock.
            return sequenceRepository.lockByPeriod(period)
                    .orElseThrow(() -> new IllegalStateException(
                            "Invoice sequence row for period " + period + " vanished after insert race"));
        }
    }

    private static String format(String period, long value) {
        // period is "YYYY-MM"; splice into "INV-YYYY-MM-NNNNNN".
        return "INV-" + period + "-" + String.format("%06d", value);
    }
}
