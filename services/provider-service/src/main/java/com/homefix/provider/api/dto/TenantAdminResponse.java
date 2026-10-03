package com.homefix.provider.api.dto;

import java.util.UUID;

import com.homefix.provider.service.TenantViews.AdminView;

/**
 * One Tenant administrator (Requirement MT-2). {@code mobileNumber} is null when the Auth Service
 * could not be asked for it.
 */
public record TenantAdminResponse(UUID userId, String mobileNumber) {

    public static TenantAdminResponse from(AdminView view) {
        return new TenantAdminResponse(view.userId(), view.mobileNumber());
    }
}
