package com.homefix.invoice.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.homefix.invoice.domain.Invoice;

/**
 * Customer-history view of a single invoice (Requirement 13.5). Carries no PDF bytes; the client
 * fetches the document through a freshly-minted signed URL.
 */
public record InvoiceSummary(
        UUID id,
        String invoiceNumber,
        UUID bookingId,
        UUID paymentId,
        Instant generatedAt,
        String downloadUrl,
        Instant downloadUrlExpiresAt) {

    public static InvoiceSummary from(Invoice invoice, String downloadUrl, Instant expiresAt) {
        return new InvoiceSummary(invoice.getId(), invoice.getInvoiceNumber(), invoice.getBookingId(),
                invoice.getPaymentId(), invoice.getGeneratedAt(), downloadUrl, expiresAt);
    }
}
