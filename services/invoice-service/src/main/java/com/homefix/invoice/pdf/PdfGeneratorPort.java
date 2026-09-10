package com.homefix.invoice.pdf;

import com.homefix.invoice.domain.InvoiceData;

/**
 * Outbound port that renders {@link InvoiceData} into PDF bytes (Requirement 13.1). Hiding the
 * concrete PDF library behind a port keeps the orchestration flow (and its retry logic)
 * unit-testable with a fake or a deliberately failing generator.
 */
public interface PdfGeneratorPort {

    /**
     * Renders the invoice to PDF.
     *
     * @throws InvoicePdfException if rendering fails (the orchestrator retries on this).
     */
    byte[] render(InvoiceData data);
}
