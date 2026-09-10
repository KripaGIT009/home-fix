package com.homefix.invoice.details;

import java.util.UUID;

/**
 * Outbound port that resolves the customer/provider/service/price-breakdown facts for a booking
 * (Requirement 13.1) from the owning services (Booking, Customer, Provider). Cross-service data is
 * fetched via API per the platform's data-ownership rule; the concrete transport is hidden so the
 * invoice-generation flow stays unit-testable.
 */
public interface InvoiceDetailsPort {

    /**
     * Resolves the additional invoice facts for a booking.
     *
     * @param bookingId  the booking being invoiced
     * @param customerId the paying customer
     * @param providerId the assigned provider
     * @return the resolved details used to render the invoice
     */
    InvoiceDetails resolve(UUID bookingId, UUID customerId, UUID providerId);
}
