package com.homefix.provider.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A Platform_Admin's create or update of a Tenant (Requirements MT-1.2, MT-1.3), as received.
 * Every field is unvalidated here; {@code TenantService} checks each rule and names the one that
 * failed. {@code status} is only read on update, where {@code null} keeps the current status.
 */
public record TenantCommand(
        String name,
        String contactPhone,
        String contactEmail,
        Double baseLatitude,
        Double baseLongitude,
        BigDecimal serviceRadiusKm,
        List<UUID> categoryIds,
        String status) {
}
