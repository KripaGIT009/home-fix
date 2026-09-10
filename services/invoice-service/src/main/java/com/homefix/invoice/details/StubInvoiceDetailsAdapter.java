package com.homefix.invoice.details;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.homefix.invoice.domain.PriceLineItem;
import org.springframework.stereotype.Component;

/**
 * Placeholder {@link InvoiceDetailsPort} adapter that returns deterministic details until the
 * Booking/Customer/Provider service HTTP clients are wired (those services own the underlying
 * data). It keeps the invoice-generation flow end-to-end runnable and is trivially replaced by an
 * HTTP adapter behind the same port.
 *
 * <p>Active only when no other {@link InvoiceDetailsPort} bean is present (tests supply a fake).
 */
@Component
public class StubInvoiceDetailsAdapter implements InvoiceDetailsPort {

    @Override
    public InvoiceDetails resolve(UUID bookingId, UUID customerId, UUID providerId) {
        return new InvoiceDetails(
                "Customer " + customerId,
                "Address on file for customer " + customerId,
                "Provider " + providerId,
                true,
                "Home service for booking " + bookingId,
                List.of(new PriceLineItem("Service charge", new BigDecimal("0.00"))),
                new BigDecimal("0.00"),
                BigDecimal.ZERO);
    }
}
