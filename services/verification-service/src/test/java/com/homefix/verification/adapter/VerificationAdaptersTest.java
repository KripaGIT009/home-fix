package com.homefix.verification.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.UUID;

import com.homefix.verification.api.dto.VerificationResponse;
import com.homefix.verification.backgroundcheck.LoggingBackgroundCheckAdapter;
import com.homefix.verification.config.VerificationProperties;
import com.homefix.verification.dispatch.LoggingDispatchPoolAdapter;
import com.homefix.verification.domain.DocumentType;
import com.homefix.verification.domain.Verification;
import com.homefix.verification.domain.VerificationStatus;
import com.homefix.verification.notification.LoggingProviderNotificationAdapter;
import com.homefix.verification.service.VerificationException;
import com.homefix.verification.storage.S3DocumentStorageAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Unit tests for the Verification Service outbound adapters, the S3 storage key/SSE contract
 * (Requirement 5.3), the tunable properties, the response mapping including the audit trail
 * (Requirement 5.11), and the domain exception factories.
 */
class VerificationAdaptersTest {

    @Test
    void loggingAdapters_doNotThrow() {
        assertThatCode(() -> new LoggingProviderNotificationAdapter()
                .notifyRejected(UUID.randomUUID(), "docs invalid")).doesNotThrowAnyException();
        assertThatCode(() -> new LoggingDispatchPoolAdapter()
                .deactivateProvider(UUID.randomUUID())).doesNotThrowAnyException();

        String ref = new LoggingBackgroundCheckAdapter().initiate(UUID.randomUUID());
        assertThat(ref).startsWith("BGC-");
    }

    @Test
    void s3StorageAdapter_buildsBucketScopedKeyAndReturnsRef() {
        VerificationProperties props = new VerificationProperties();
        props.setDocumentsBucket("homefix-docs-test");
        S3DocumentStorageAdapter adapter = new S3DocumentStorageAdapter(props);
        UUID provider = UUID.randomUUID();

        String ref = adapter.store(provider, DocumentType.GOVERNMENT_ID, new byte[]{1, 2, 3}, "image/jpeg");

        assertThat(ref)
                .startsWith("s3://homefix-docs-test/")
                .contains(provider.toString())
                .contains(DocumentType.GOVERNMENT_ID.name());
    }

    @Test
    void verificationProperties_exposeDefaultsAndRoundTrip() {
        VerificationProperties p = new VerificationProperties();
        assertThat(p.getDocumentsBucket()).isEqualTo("homefix-documents");
        assertThat(p.getRequiredDocumentTypes())
                .containsExactly("GOVERNMENT_ID", "ADDRESS_PROOF", "SKILL_CERTIFICATION");

        p.setDocumentsBucket("b");
        p.setRequiredDocumentTypes(List.of("GOVERNMENT_ID"));
        assertThat(p.getDocumentsBucket()).isEqualTo("b");
        assertThat(p.getRequiredDocumentTypes()).containsExactly("GOVERNMENT_ID");
    }

    @Test
    void verificationResponse_mapsStatusAndAuditTrail() {
        UUID provider = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        Verification v = Verification.create(provider);
        // Drive a transition so the audit trail has an entry to map.
        v.transitionTo(VerificationStatus.DOCUMENT_SUBMITTED, provider, "submitted");
        v.transitionTo(VerificationStatus.DOCUMENT_VERIFIED, admin, "verified");

        VerificationResponse response = VerificationResponse.from(v);

        assertThat(response.providerId()).isEqualTo(provider);
        assertThat(response.status()).isEqualTo("DOCUMENT_VERIFIED");
        assertThat(response.auditTrail()).isNotEmpty();
        assertThat(response.auditTrail())
                .anySatisfy(entry -> assertThat(entry.toState()).isEqualTo("DOCUMENT_VERIFIED"));
    }

    @Test
    void verificationException_factoriesCarryStatusAndErrorCode() {
        assertThat(VerificationException.notFound("x").getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(VerificationException.notFound("x").getErrorCode()).isEqualTo("VERIFICATION_NOT_FOUND");
        assertThat(VerificationException.validation("x").getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(VerificationException.validation("x").getErrorCode()).isEqualTo("VALIDATION_ERROR");
    }
}
