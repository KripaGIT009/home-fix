package com.homefix.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for {@code POST /auth/register/verify}.
 *
 * @param mobileNumber the number the OTP was sent to
 * @param otp          the numeric code the user received
 */
public record VerifyRequest(

        @NotBlank(message = "mobileNumber is required")
        @Pattern(regexp = "^\\+[1-9]\\d{7,14}$",
                message = "mobileNumber must be a valid E.164 number")
        String mobileNumber,

        @NotBlank(message = "otp is required")
        @Pattern(regexp = "^\\d{4,10}$", message = "otp must be 4-10 digits")
        String otp) {
}
