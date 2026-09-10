package com.homefix.verification.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.homefix.verification.api.dto.VerificationResponse;
import com.homefix.verification.domain.DocumentType;
import com.homefix.verification.domain.Verification;
import com.homefix.verification.service.DocumentUpload;
import com.homefix.verification.service.VerificationException;
import com.homefix.verification.service.VerificationService;

/**
 * Provider-facing verification endpoints (Requirement 5.3, 5.10, 5.11).
 */
@RestController
@RequestMapping("/verifications/{providerId}")
public class VerificationController {

    private final VerificationService verificationService;

    public VerificationController(VerificationService verificationService) {
        this.verificationService = verificationService;
    }

    /**
     * {@code POST /verifications/{providerId}/documents} — upload Government ID, address proof,
     * and skill certification; stored in S3 with SSE, then transition to
     * {@code DOCUMENT_SUBMITTED} (Requirement 5.3).
     *
     * <p>Documents are supplied as multipart files whose part names are the document type
     * (e.g. {@code GOVERNMENT_ID}, {@code ADDRESS_PROOF}, {@code SKILL_CERTIFICATION}).
     */
    @PostMapping(path = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<VerificationResponse> submitDocuments(
            @PathVariable("providerId") UUID providerId,
            @RequestParam Map<String, MultipartFile> files) {
        List<DocumentUpload> uploads = new ArrayList<>();
        for (Map.Entry<String, MultipartFile> entry : files.entrySet()) {
            DocumentType type = parseType(entry.getKey());
            MultipartFile file = entry.getValue();
            try {
                uploads.add(new DocumentUpload(type, file.getBytes(), file.getContentType()));
            } catch (IOException e) {
                throw new VerificationException(HttpStatus.BAD_REQUEST, "DOCUMENT_READ_FAILED",
                        "Could not read uploaded document: " + entry.getKey());
            }
        }
        Verification verification = verificationService.submitDocuments(providerId, uploads, providerId);
        return ResponseEntity.status(HttpStatus.CREATED).body(VerificationResponse.from(verification));
    }

    /** {@code GET /verifications/{providerId}} — read the current verification record. */
    @GetMapping
    public ResponseEntity<VerificationResponse> get(@PathVariable("providerId") UUID providerId) {
        return ResponseEntity.ok(VerificationResponse.from(verificationService.getByProviderId(providerId)));
    }

    /**
     * {@code GET /verifications/{providerId}/job-assignment-eligibility} — returns whether the
     * provider may receive job assignments (Requirement 5.10). Returns 403 if not APPROVED.
     */
    @GetMapping("/job-assignment-eligibility")
    public ResponseEntity<Void> assertJobAssignmentEligible(@PathVariable("providerId") UUID providerId) {
        verificationService.assertCanReceiveJobAssignment(providerId);
        return ResponseEntity.noContent().build();
    }

    private DocumentType parseType(String raw) {
        try {
            return DocumentType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw VerificationException.validation("Unknown document type: " + raw);
        }
    }
}
