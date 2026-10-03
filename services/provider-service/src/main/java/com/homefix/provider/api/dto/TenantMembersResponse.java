package com.homefix.provider.api.dto;

import java.util.List;

/** Body of {@code GET /admin/tenants/{id}/members} (Requirement MT-12.3). */
public record TenantMembersResponse(List<TenantAdminResponse> admins, List<TeamProviderResponse> providers) {
}
