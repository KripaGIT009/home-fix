package com.homefix.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for {@code POST /auth/register/otp}.
 *
 * @param mobileNumber E.164 mobile number, e.g. {@code +919876543210}
 * @param role         optional target role; defaults to CUSTOMER when omitted
 */
public record OtpRequest(

        @NotBlank(message = "mobileNumber is required")
        @Pattern(regexp = "^\\+[1-9]\\d{7,14}$",
                message = "mobileNumber must be a valid E.164 number, e.g. +919876543210")
        String mobileNumber,

        String role) {
}
