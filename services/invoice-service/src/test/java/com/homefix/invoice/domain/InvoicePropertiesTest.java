package com.homefix.invoice.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.homefix.invoice.support.InMemoryInvoiceSequenceRepository;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based test for the Invoice Service correctness property 14 (design.md "Correctness
 * Properties", Requirement 13.4). Runs a minimum of 100 tries and is tagged with the required
 * {@code Feature: homefix-platform, Property 14} label.
 *
 * <p>Exercises {@link InvoiceNumberGenerator} against a fresh in-memory sequence repository (the
 * same fake the example-based {@link InvoiceNumberGeneratorTest} uses), so the claim + increment
 * behaves exactly as it does against the real pessimistic-locking repository, without a database.
 */
class InvoicePropertiesTest {

    private static final DateTimeFormatter PERIOD =
            DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC);

    // ============================================================================================
    // Property 14: Invoice number uniqueness and format
    // ============================================================================================

    @Property(tries = 100)
    @Label("Feature: homefix-platform, Property 14: Invoice number uniqueness and format")
    void invoiceNumbersAreUniqueAndCorrectlyFormattedWithMonotonicPerMonthSequence(
            @ForAll("generationInstants") List<Instant> instants) {

        InvoiceNumberGenerator generator =
                new InvoiceNumberGenerator(new InMemoryInvoiceSequenceRepository());

        Set<String> allNumbers = new HashSet<>();
        // Per-period, the sequence numbers handed out, in generation order.
        Map<String, List<Long>> sequencesByPeriod = new TreeMap<>();

        for (Instant at : instants) {
            String number = generator.nextInvoiceNumber(at);

            // Format: INV-YYYY-MM-NNNNNN
            assertThat(number).matches(InvoiceNumberGenerator.FORMAT);

            // Global uniqueness: no two invoices share a number.
            assertThat(allNumbers.add(number))
                    .as("invoice number %s was handed out more than once", number)
                    .isTrue();

            String period = PERIOD.format(at);
            // The number embeds the period of its generation instant.
            assertThat(number).startsWith("INV-" + period + "-");

            long sequence = Long.parseLong(number.substring(number.length() - 6));
            sequencesByPeriod.computeIfAbsent(period, p -> new ArrayList<>()).add(sequence);
        }

        // Per month, the sequence starts at 1 and increases by exactly 1 each time (monotonic).
        for (Map.Entry<String, List<Long>> entry : sequencesByPeriod.entrySet()) {
            List<Long> sequences = entry.getValue();
            assertThat(sequences.get(0))
                    .as("first sequence for period %s must start at 1", entry.getKey())
                    .isEqualTo(1L);
            for (int i = 1; i < sequences.size(); i++) {
                assertThat(sequences.get(i))
                        .as("sequence for period %s must increase by exactly 1", entry.getKey())
                        .isEqualTo(sequences.get(i - 1) + 1);
            }
        }
    }

    // ============================================================================================
    // Generators
    // ============================================================================================

    /**
     * Instants drawn from a small set of months across a few years so that generated batches
     * reliably mix multiple periods (exercising the per-month sequence reset) while still producing
     * many invoices within a single month (exercising monotonic increment).
     */
    @Provide
    Arbitrary<List<Instant>> generationInstants() {
        Arbitrary<Integer> year = Arbitraries.integers().between(2023, 2026);
        Arbitrary<Integer> month = Arbitraries.integers().between(1, 12);
        Arbitrary<Integer> day = Arbitraries.integers().between(1, 28);
        Arbitrary<Integer> hour = Arbitraries.integers().between(0, 23);
        Arbitrary<Instant> instant = Combinators.combine(year, month, day, hour)
                .as((y, mo, d, h) -> Instant.parse(
                        String.format("%04d-%02d-%02dT%02d:00:00Z", y, mo, d, h)));
        return instant.list().ofMinSize(1).ofMaxSize(60);
    }
}
