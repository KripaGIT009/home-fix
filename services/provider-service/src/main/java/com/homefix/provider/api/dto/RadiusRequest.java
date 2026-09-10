package com.homefix.provider.api.dto;

/**
 * Request body for {@code PUT /providers/{id}/radius} (Requirement 4.4). Range validation is
 * performed in the service layer so the same permitted-range error is returned everywhere.
 */
public record RadiusRequest(int serviceRadiusKm) {
}
