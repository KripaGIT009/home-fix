package com.homefix.auth.api;

import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request and response bodies of the email-auth endpoints (email-auth design, "Public endpoints",
 * "Signed-in endpoints", "Admin endpoints", "Internal"). Shape checks only; the rules (password
 * policy, roles, uniqueness) are the services'.
 */
public final class EmailAuthRequests {

    private static final String E164 = "^\\+[1-9]\\d{7,14}$";
    private static final String SIX_DIGITS = "^\\d{6}$";

    private EmailAuthRequests() {
    }

    /** {@code POST /auth/register/email}. */
    public record EmailSignupRequest(
            @NotBlank(message = "displayName is required")
            @Size(min = 2, max = 80, message = "displayName must be 2-80 characters")
            String displayName,

            @NotBlank(message = "email is required")
            @Email(message = "email must be a valid email address")
            @Size(max = 254, message = "email must be at most 254 characters")
            String email,

            @NotBlank(message = "mobileNumber is required")
            @Pattern(regexp = E164, message = "mobileNumber must be a valid E.164 number, e.g. +919876543210")
            String mobileNumber,

            @NotBlank(message = "password is required")
            @Size(max = 128, message = "password must be at most 128 characters")
            String password,

            String role) {
    }

    /** {@code POST /auth/register/email/verify} and the reset code check. */
    public record EmailCodeRequest(
            @NotBlank(message = "email is required")
            @Size(max = 254, message = "email must be at most 254 characters")
            String email,

            @NotBlank(message = "code is required")
            @Pattern(regexp = SIX_DIGITS, message = "code must be 6 digits")
            String code) {
    }

    /** {@code POST /auth/register/email/resend} and {@code POST /auth/password/forgot}. */
    public record EmailOnlyRequest(
            @NotBlank(message = "email is required")
            @Email(message = "email must be a valid email address")
            @Size(max = 254, message = "email must be at most 254 characters")
            String email) {
    }

    /** {@code POST /auth/password/reset}. */
    public record PasswordResetRequest(
            @NotBlank(message = "email is required")
            @Size(max = 254, message = "email must be at most 254 characters")
            String email,

            @NotBlank(message = "code is required")
            @Pattern(regexp = SIX_DIGITS, message = "code must be 6 digits")
            String code,

            @NotBlank(message = "newPassword is required")
            @Size(max = 128, message = "newPassword must be at most 128 characters")
            String newPassword) {
    }

    /** {@code POST /auth/me/email}. */
    public record EmailChangeRequest(
            @NotBlank(message = "email is required")
            @Email(message = "email must be a valid email address")
            @Size(max = 254, message = "email must be at most 254 characters")
            String email,

            @Size(max = 128, message = "currentPassword must be at most 128 characters")
            String currentPassword) {
    }

    /** {@code POST /auth/me/email/verify}. */
    public record CodeOnlyRequest(
            @NotBlank(message = "code is required")
            @Pattern(regexp = SIX_DIGITS, message = "code must be 6 digits")
            String code) {
    }

    /** {@code PUT /auth/me/password}. */
    public record PasswordChangeRequest(
            @Size(max = 128, message = "currentPassword must be at most 128 characters")
            String currentPassword,

            @NotBlank(message = "newPassword is required")
            @Size(max = 128, message = "newPassword must be at most 128 characters")
            String newPassword) {
    }

    /** {@code POST /auth/invitations/{token}/acceptance}: name and mobile only for a new account. */
    public record InvitationAcceptanceRequest(
            @Size(max = 80, message = "displayName must be at most 80 characters")
            String displayName,

            @Size(max = 20, message = "mobileNumber must be at most 20 characters")
            String mobileNumber,

            @NotBlank(message = "password is required")
            @Size(max = 128, message = "password must be at most 128 characters")
            String password) {
    }

    /** {@code POST /admin/invitations}. */
    public record InvitationRequest(
            @NotBlank(message = "email is required")
            @Email(message = "email must be a valid email address")
            @Size(max = 254, message = "email must be at most 254 characters")
            String email,

            @NotBlank(message = "role is required")
            String role) {
    }

    /** {@code POST /internal/emails/agency-decision}. */
    public record AgencyDecisionEmailRequest(
            @NotNull(message = "userId is required")
            UUID userId,

            @NotBlank(message = "tenantName is required")
            @Size(max = 120, message = "tenantName must be at most 120 characters")
            String tenantName,

            boolean approved,

            @Size(max = 500, message = "reason must be at most 500 characters")
            String reason) {
    }

    /**
     * 202 answer to every request that may send a code. Identical whether or not a code was sent,
     * so it says nothing about the address (Property EA1).
     */
    public record CodeSentResponse(String status, long expiresInSeconds) {

        public static CodeSentResponse of(long expiresInSeconds) {
            return new CodeSentResponse("CODE_SENT", expiresInSeconds);
        }
    }
}
