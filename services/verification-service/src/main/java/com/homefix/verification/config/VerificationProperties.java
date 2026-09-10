package com.homefix.verification.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunable verification-domain settings (Requirement 5).
 *
 * <p>Defaults match the acceptance criteria; all values are overridable via configuration
 * so operations can tune behaviour without a code change.
 */
@ConfigurationProperties(prefix = "homefix.verification")
public class VerificationProperties {

    /** Target S3 bucket for uploaded verification documents (Requirement 5.3). */
    private String documentsBucket = "homefix-documents";

    /**
     * Document types a provider must upload before the status can move to
     * {@code DOCUMENT_SUBMITTED} (Requirement 5.3).
     */
    private List<String> requiredDocumentTypes =
            List.of("GOVERNMENT_ID", "ADDRESS_PROOF", "SKILL_CERTIFICATION");

    public String getDocumentsBucket() {
        return documentsBucket;
    }

    public void setDocumentsBucket(String documentsBucket) {
        this.documentsBucket = documentsBucket;
    }

    public List<String> getRequiredDocumentTypes() {
        return requiredDocumentTypes;
    }

    public void setRequiredDocumentTypes(List<String> requiredDocumentTypes) {
        this.requiredDocumentTypes = requiredDocumentTypes;
    }
}
