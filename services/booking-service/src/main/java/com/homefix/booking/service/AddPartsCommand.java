package com.homefix.booking.service;

import java.math.BigDecimal;

/**
 * A single parts/materials line item a provider adds during job execution (Requirement 6.8,
 * 11.3). Validation of the minimums (quantity ≥ 1, unit cost ≥ 0.01) is performed in the
 * service so a descriptive {@link BookingException} is returned.
 */
public record AddPartsCommand(String itemName, int quantity, BigDecimal unitCost) {
}
