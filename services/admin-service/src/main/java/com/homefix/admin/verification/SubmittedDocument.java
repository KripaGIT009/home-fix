package com.homefix.admin.verification;

/**
 * A single document submitted by a Provider for verification, exposed for inline viewing within
 * the Admin dashboard (Requirement 19.3).
 *
 * @param documentType e.g. GOVERNMENT_ID, ADDRESS_PROOF, SKILL_CERTIFICATION
 * @param inlineViewUrl a signed URL that renders the document inline in the dashboard, so no
 *                      separate download step is required
 * @param contentType the MIME type used to render the document inline (e.g. image/jpeg,
 *                    application/pdf)
 */
public record SubmittedDocument(
        String documentType,
        String inlineViewUrl,
        String contentType) {
}
