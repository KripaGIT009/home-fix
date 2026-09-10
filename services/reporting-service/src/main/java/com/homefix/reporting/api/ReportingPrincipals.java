package com.homefix.reporting.api;

import org.springframework.security.core.Authentication;

/**
 * Resolves the acting principal's identity from the security context for the Reporting Service.
 *
 * <p>The shared {@code JwtValidationFilter} sets the authentication name to the JWT subject (the
 * user UUID). The asynchronous path emails the requestor a download link (Requirement 20.3); the
 * concrete email address is resolved downstream by the email adapter from this stable, non-PII
 * subject reference, so no email address is accepted from the request body or logged
 * (Requirement 26.4).
 */
public final class ReportingPrincipals {

    private ReportingPrincipals() {
    }

    /**
     * Returns the requestor reference (the JWT subject) used by the asynchronous email adapter to
     * resolve the recipient. Returns {@code null} for an unauthenticated principal, which cannot
     * reach a controller because the RBAC filter rejects such calls upstream.
     */
    public static String requestorReference(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        return authentication.getName();
    }
}
