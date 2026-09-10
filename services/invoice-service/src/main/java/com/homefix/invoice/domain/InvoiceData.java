package com.homefix.invoice.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The fully-resolved content required to render an invoice PDF (Requirement 13.1).
 *
 * <p>Carries the invoice number and date, the customer's name/address, the provider's name and
 * verified status, the service description, the itemized price breakdown, tax and discount
 * amounts, the final total, and the payment method. The {@link #discountAmount} is rendered only
 * when non-zero (Requirement 13.1).
 */
public record InvoiceData(
        String invoiceNumber,
        Instant invoiceDate,
        UUID bookingId,
        UUID paymentId,
        UUID customerId,
        UUID providerId,
        String customerName,
        String customerAddress,
        String providerName,
        boolean providerVerified,
        String serviceDescription,
        List<PriceLineItem> lineItems,
        BigDecimal taxAmount,
        String taxIdentifier,
        BigDecimal discountAmount,
        BigDecimal totalAmount,
        String paymentMethod) {

    /** True when a discount line should be rendered (Requirement 13.1: omit if zero). */
    public boolean hasDiscount() {
        return discountAmount != null && discountAmount.signum() != 0;
    }
}
