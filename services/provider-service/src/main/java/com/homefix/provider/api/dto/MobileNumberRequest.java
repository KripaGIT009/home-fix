package com.homefix.provider.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of the add-by-mobile-number endpoints: a Tenant administrator (Requirement MT-2.2) or a team
 * member (Requirement MT-3.2), identified by the mobile number their account is registered with.
 */
public record MobileNumberRequest(
        @NotBlank @Size(max = 32) String mobileNumber) {
}
