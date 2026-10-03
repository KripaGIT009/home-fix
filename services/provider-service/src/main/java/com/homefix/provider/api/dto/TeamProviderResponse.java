package com.homefix.provider.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.homefix.provider.service.TenantViews.TeamProviderView;

/**
 * One Provider of a Tenant's team ({@code TeamProvider} in the design, Requirements MT-3.4,
 * MT-8.4).
 *
 * <p>{@code verificationStatus} is the Verification Service's raw status ({@code APPROVED},
 * {@code SUSPENDED}, ...), or null when the provider has none or it could not be asked.
 * {@code mobileNumber} is null in lists (the Auth Service owns it) and set in the answer to an add
 * by mobile number. {@code assignable} is what booking-service enforces on assignment.
 */
public record TeamProviderResponse(
        UUID providerId,
        String displayName,
        String mobileNumber,
        String primarySkill,
        String verificationStatus,
        BigDecimal rating,
        boolean availableNow,
        boolean assignable) {

    public static TeamProviderResponse from(TeamProviderView v) {
        return new TeamProviderResponse(v.providerId(), v.displayName(), v.mobileNumber(), v.primarySkill(),
                v.verificationStatus(), v.rating(), v.availableNow(), v.assignable());
    }
}
