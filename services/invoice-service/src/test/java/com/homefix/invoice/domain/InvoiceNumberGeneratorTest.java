package com.homefix.invoice.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;

import com.homefix.invoice.support.InMemoryInvoiceSequenceRepository;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InvoiceNumberGenerator} (Requirement 13.4, Property 14):
 * format {@code INV-YYYY-MM-NNNNNN} and monotonically-increasing, unique sequence per month.
 */
class InvoiceNumberGeneratorTest {

    private final InvoiceNumberGenerator generator =
            new InvoiceNumberGenerator(new InMemoryInvoiceSequenceRepository());

    private static final Instant JULY_2024 = Instant.parse("2024-07-15T10:00:00Z");
    private static final Instant AUGUST_2024 = Instant.parse("2024-08-01T00:00:00Z");

    @Test
    void assignsNumbersMatchingTheCanonicalFormat() {
        String number = generator.nextInvoiceNumber(JULY_2024);

        assertThat(number).matches(InvoiceNumberGenerator.FORMAT);
        assertThat(number).isEqualTo("INV-2024-07-000001");
    }

    @Test
    void sequenceIncreasesMonotonicallyWithinAMonth() {
        String first = generator.nextInvoiceNumber(JULY_2024);
        String second = generator.nextInvoiceNumber(JULY_2024.plus(1, ChronoUnit.HOURS));
        String third = generator.nextInvoiceNumber(JULY_2024.plus(2, ChronoUnit.HOURS));

        assertThat(first).isEqualTo("INV-2024-07-000001");
        assertThat(second).isEqualTo("INV-2024-07-000002");
        assertThat(third).isEqualTo("INV-2024-07-000003");
    }

    @Test
    void allNumbersWithinAMonthAreUnique() {
        Set<String> numbers = new HashSet<>();
        int count = 250;
        for (int i = 0; i < count; i++) {
            String number = generator.nextInvoiceNumber(JULY_2024);
            assertThat(number).matches(InvoiceNumberGenerator.FORMAT);
            numbers.add(number);
        }
        assertThat(numbers).hasSize(count);
        assertThat(numbers).contains("INV-2024-07-000001", "INV-2024-07-000250");
    }

    @Test
    void sequenceRestartsPerMonth() {
        generator.nextInvoiceNumber(JULY_2024);
        generator.nextInvoiceNumber(JULY_2024);

        String august = generator.nextInvoiceNumber(AUGUST_2024);

        assertThat(august).isEqualTo("INV-2024-08-000001");
    }
}
