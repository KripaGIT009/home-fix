package com.homefix.invoice.domain;

import java.math.BigDecimal;

/**
 * A single labeled component of the itemized price breakdown rendered on the invoice
 * (Requirement 13.1), e.g. {@code ("Base price", 500.00)} or {@code ("Discount", -50.00)}.
 */
public record PriceLineItem(String label, BigDecimal amount) {
}
