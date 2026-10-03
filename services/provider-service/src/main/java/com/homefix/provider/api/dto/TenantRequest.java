package com.homefix.provider.api.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.homefix.provider.service.TenantCommand;

/**
 * Body of {@code POST /admin/tenants} and {@code PUT /admin/tenants/{id}} (Requirements MT-1.2,
 * MT-1.3). Deliberately free of bean-validation annotations: {@code TenantService} checks each rule
 * and answers an errorCode naming the one that failed, which a generic {@code VALIDATION_ERROR}
 * could not. {@code status} is read on update only; omitted, the status is kept.
 */
public record TenantRequest(
        String name,
        String contactPhone,
        String contactEmail,
        Double baseLatitude,
        Double baseLongitude,
        BigDecimal serviceRadiusKm,
        List<UUID> categoryIds,
        String status) {

    public TenantCommand toCommand() {
        return new TenantCommand(name, contactPhone, contactEmail, baseLatitude, baseLongitude,
                serviceRadiusKm, categoryIds, status);
    }
}
