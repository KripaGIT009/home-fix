package com.homefix.provider.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.homefix.provider.service.ProviderAdminService.AdminProviderView;

/**
 * One provider of {@code GET /admin/providers} (and the body of
 * {@code PATCH /admin/providers/{id}/status}), shaped exactly as the Admin Portal's
 * {@code AdminProvider} (Requirement 19.2).
 *
 * <p>Fields this service does not own are {@code null}: {@code mobileNumber} (Auth Service),
 * {@code completedJobs} (Booking Service) and {@code isOnline} (Location Service).
 * {@code verificationStatus} and {@code status} are derived from the Verification Service and are
 * {@code null} when it could not be asked. {@code bankAccount} is the settlement account on file,
 * masked, or {@code null} when the provider has not added one.
 */
public record AdminProviderResponse(
        UUID id,
        String displayName,
        String mobileNumber,
        String primarySkill,
        String verificationStatus,
        BigDecimal rating,
        Integer completedJobs,
        @JsonProperty("isOnline") Boolean isOnline,
        String status,
        BankAccountSummary bankAccount) {

    /**
     * The portal's {@code bankAccount}: what an administrator needs to decide on verification.
     *
     * @param masked   e.g. {@code HDFC ••••6789}, never the number or the ciphertext
     * @param verified whether the account may receive settlements
     */
    public record BankAccountSummary(String masked, boolean verified) {
    }

    public static AdminProviderResponse from(AdminProviderView view) {
        String raw = view.verificationStatus();
        boolean known = view.verificationKnown();
        return new AdminProviderResponse(
                view.row().id(),
                view.row().displayName(),
                null,
                view.primarySkill(),
                known ? portalVerificationStatus(raw) : null,
                view.row().aggregateRating(),
                null,
                null,
                known ? portalStatus(raw) : null,
                view.bankAccount() == null
                        ? null
                        : new BankAccountSummary(view.bankAccount().masked(), view.bankAccount().verified()));
    }

    /**
     * Maps the Verification Service's status onto the portal's five values
     * ({@code PENDING | DOCUMENT_SUBMITTED | APPROVED | REJECTED | SUSPENDED}). No record means the
     * provider has not submitted yet: {@code PENDING}. The in-review steps after submission
     * ({@code DOCUMENT_VERIFIED}, {@code BACKGROUND_CHECK_*}) have no portal value and are shown as
     * {@code DOCUMENT_SUBMITTED} — "submitted, decision pending". An unrecognised value is
     * {@code null} rather than a guess.
     */
    static String portalVerificationStatus(String raw) {
        if (raw == null) {
            return "PENDING";
        }
        return switch (raw) {
            case "PENDING", "DOCUMENT_SUBMITTED", "APPROVED", "REJECTED", "SUSPENDED" -> raw;
            case "DOCUMENT_VERIFIED", "BACKGROUND_CHECK_PENDING", "BACKGROUND_CHECK_COMPLETED" ->
                    "DOCUMENT_SUBMITTED";
            default -> null;
        };
    }

    /**
     * The account status: {@code SUSPENDED} when the verification is suspended, otherwise
     * {@code ACTIVE}. The platform has no other account-level state for providers, so
     * {@code DEACTIVATED} is never reported.
     */
    static String portalStatus(String raw) {
        return "SUSPENDED".equals(raw) ? "SUSPENDED" : "ACTIVE";
    }
}
