package com.homefix.provider.api.dto;

/** Body of {@code POST /admin/tenants/{id}/rejection}; the reason is checked by the service. */
public record TenantRejectionRequest(String reason) {
}
