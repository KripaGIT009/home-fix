package com.homefix.invoice.notification;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbound port for delivering the invoice signed URL to the customer via the Notification Service
 * (Requirement 13.3). Hidden behind a port so the delivery-within-60 s flow is unit-testable.
 */
public interface InvoiceNotificationPort {

    /**
     * Delivers a ready invoice to the customer.
     *
     * @param customerId    the recipient customer
     * @param bookingId     the booking the invoice belongs to
     * @param invoiceNumber the assigned invoice number
     * @param signedUrl     the time-limited download URL
     * @param expiresAt     the signed URL's absolute expiry (72 h from issuance)
     */
    void deliverInvoice(UUID customerId, UUID bookingId, String invoiceNumber, String signedUrl,
                        Instant expiresAt);
}
