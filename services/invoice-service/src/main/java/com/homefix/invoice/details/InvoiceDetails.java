package com.homefix.invoice.details;

import java.math.BigDecimal;
import java.util.List;

import com.homefix.invoice.domain.PriceLineItem;

/**
 * The customer, provider, service, and price-breakdown facts needed to render an invoice that are
 * not carried on the {@code PaymentCompleted} event (Requirement 13.1). Resolved from the Booking,
 * Customer, and Provider services via {@link InvoiceDetailsPort}.
 */
public record InvoiceDetails(
        String customerName,
        String customerAddress,
        String providerName,
        boolean providerVerified,
        String serviceDescription,
        List<PriceLineItem> lineItems,
        BigDecimal taxAmount,
        BigDecimal discountAmount) {
}
