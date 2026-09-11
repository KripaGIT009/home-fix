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
 *
 * <p>Coarse role enforcement is applied by the shared {@code RbacEnforcementFilter} from the rules
 * in {@code VerificationRbacConfig}. Because every path here carries a {@code {providerId}}, a role
 * check alone is not sufficient: {@link #submitDocuments} and {@link #get} additionally assert
 * ownership through {@link CallerIdentity#requireSelfOrStaff(UUID)} as their first statement, so a
 * provider cannot submit documents against, or read, another provider's record. The dispatch gate
 * {@link #assertJobAssignmentEligible} is the deliberate exception — see its Javadoc.
 */
@RestController
@RequestMapping("/verifications/{providerId}")
public class VerificationController {

    private final VerificationService verificationService;
    private final CallerIdentity callerIdentity;

    public VerificationController(VerificationService verificationService,
                                  CallerIdentity callerIdentity) {
        this.verificationService = verificationService;
        this.callerIdentity = callerIdentity;
    }

    /**
     * {@code POST /verifications/{providerId}/documents} — upload Government ID, address proof,
     * and skill certification; stored in S3 with SSE, then transition to
     * {@code DOCUMENT_SUBMITTED} (Requirement 5.3).
     *
     * <p>Documents are supplied as multipart files whose part names are the document type
     * (e.g. {@code GOVERNMENT_ID}, {@code ADDRESS_PROOF}, {@code SKILL_CERTIFICATION}).
     *
     * <p>Ownership is asserted first: only the provider themselves (or staff) may submit documents
     * for {@code providerId}, because the submission is what the approval decision is made on.
     */
    @PostMapping(path = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<VerificationResponse> submitDocuments(
            @PathVariable("providerId") UUID providerId,
            @RequestParam Map<String, MultipartFile> files) {
        callerIdentity.requireSelfOrStaff(providerId);
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

    /**
     * {@code GET /verifications/{providerId}} — read the current verification record.
     *
     * <p>Ownership is asserted first: the record carries document references and the
     * background-check result, so only the provider themselves (or staff) may read it.
     */
    @GetMapping
    public ResponseEntity<VerificationResponse> get(@PathVariable("providerId") UUID providerId) {
        callerIdentity.requireSelfOrStaff(providerId);
        return ResponseEntity.ok(VerificationResponse.from(verificationService.getByProviderId(providerId)));
    }

    /**
     * {@code GET /verifications/{providerId}/job-assignment-eligibility} — returns whether the
     * provider may receive job assignments (Requirement 5.10). Returns 403 if not APPROVED.
     *
     * <p><strong>Deliberately carries no ownership assertion.</strong> This is the gate the Dispatch
     * Engine calls before offering a job to a candidate provider, so the caller is by design
     * <em>not</em> the provider being asked about; {@code DISPATCHER} is an admitted role in
     * {@code VerificationRbacConfig} for exactly this path. Adding
     * {@code callerIdentity.requireSelfOrStaff(providerId)} here would break dispatch. The exposure
     * is bounded: the response body is empty and the only information conveyed is a boolean
     * eligibility verdict (204 when eligible, 403 when not) — never documents, audit entries, or the
     * background-check result, all of which live behind the ownership-checked
     * {@link #get(UUID)}.
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
