package com.homefix.invoice.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.homefix.invoice.domain.InvoiceData;
import com.homefix.invoice.domain.PriceLineItem;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OpenPdfInvoiceGeneratorAdapter}: it renders a non-empty, valid PDF for
 * both the discount and no-discount cases (Requirement 13.1).
 */
class OpenPdfInvoiceGeneratorAdapterTest {

    private final OpenPdfInvoiceGeneratorAdapter adapter = new OpenPdfInvoiceGeneratorAdapter();

    @Test
    void rendersAValidPdfWithoutDiscount() {
        byte[] pdf = adapter.render(data(BigDecimal.ZERO));

        assertThat(pdf).isNotEmpty();
        // A well-formed PDF starts with the "%PDF" magic header.
        assertThat(new String(pdf, 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
    }

    @Test
    void rendersAValidPdfWithDiscount() {
        byte[] pdf = adapter.render(data(new BigDecimal("50.00")));

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
    }

    private static InvoiceData data(BigDecimal discount) {
        return new InvoiceData(
                "INV-2024-07-000001",
                Instant.parse("2024-07-15T10:00:00Z"),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Jane Customer",
                "42 Test Street",
                "John Provider",
                true,
                "Leaky faucet repair",
                List.of(new PriceLineItem("Base price", new BigDecimal("500.00")),
                        new PriceLineItem("Distance charge", new BigDecimal("40.00"))),
                new BigDecimal("90.00"),
                "GSTIN-TEST",
                discount,
                new BigDecimal("580.00"),
                "UPI");
    }
}
