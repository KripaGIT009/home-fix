package com.homefix.complaint.api;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.complaint.api.dto.ChangeStatusRequest;
import com.homefix.complaint.api.dto.ComplaintResponse;
import com.homefix.complaint.api.dto.ComplaintStatsResponse;
import com.homefix.complaint.api.dto.CreateComplaintRequest;
import com.homefix.complaint.api.dto.RefundRequest;
import com.homefix.complaint.domain.Complaint;
import com.homefix.complaint.service.AttachmentMetadata;
import com.homefix.complaint.service.ComplaintException;
import com.homefix.complaint.service.ComplaintService;
import com.homefix.complaint.service.CreateComplaintCommand;

import jakarta.validation.Valid;

/**
 * REST surface for the Complaint Service (Requirement 16).
 *
 * <p>Complaint creation derives the authenticated customer from the JWT principal populated by the
 * shared {@code JwtValidationFilter} (Task 4). Support_Agent operations (status change, refund
 * approval, dispute, closure) and the Admin stats endpoint are additionally gated by the shared
 * {@code RbacEnforcementFilter}.
 */
@RestController
@RequestMapping("/complaints")
public class ComplaintController {

    private final ComplaintService complaintService;

    public ComplaintController(ComplaintService complaintService) {
        this.complaintService = complaintService;
    }

    /** Raise a complaint against a completed booking (Requirement 16.1). */
    @PostMapping
    public ResponseEntity<ComplaintResponse> create(@Valid @RequestBody CreateComplaintRequest req) {
        List<AttachmentMetadata> attachments = req.attachments() == null ? List.of()
                : req.attachments().stream()
                        .map(a -> new AttachmentMetadata(a.fileName(), a.sizeBytes()))
                        .collect(Collectors.toList());
        CreateComplaintCommand command = new CreateComplaintCommand(req.bookingId(), currentUser(),
                req.providerId(), req.category(), req.priority(), req.description(), attachments);
        Complaint complaint = complaintService.createComplaint(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(ComplaintResponse.from(complaint));
    }

    /** Support_Agent: update a complaint's status; customer is notified (Requirement 16.3). */
    @PostMapping("/{complaintId}/status")
    public ComplaintResponse changeStatus(@PathVariable UUID complaintId,
                                          @Valid @RequestBody ChangeStatusRequest req) {
        return ComplaintResponse.from(complaintService.changeStatus(complaintId, req.status()));
    }

    /** Support_Agent: approve a refund; coordinates with the Payment Service (16.5, 16.6). */
    @PostMapping("/{complaintId}/refund")
    public ResponseEntity<Void> approveRefund(@PathVariable UUID complaintId,
                                              @Valid @RequestBody RefundRequest req) {
        complaintService.approveRefund(complaintId, req.amount());
        return ResponseEntity.accepted().build();
    }

    /** Support_Agent: set the complaint to DISPUTED, holding the provider settlement (16.7). */
    @PostMapping("/{complaintId}/dispute")
    public ComplaintResponse dispute(@PathVariable UUID complaintId) {
        return ComplaintResponse.from(complaintService.markDisputed(complaintId));
    }

    /** Admin: aggregated complaint statistics for reports (Requirement 16.9). */
    @GetMapping("/stats")
    public ComplaintStatsResponse stats() {
        return ComplaintStatsResponse.from(complaintService.aggregateStats());
    }

    /**
     * @return the authenticated user's ID, taken from the principal name populated by the shared
     *         {@code JwtValidationFilter} (Task 4).
     */
    private UUID currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ComplaintException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "no authenticated principal");
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            throw new ComplaintException(HttpStatus.UNAUTHORIZED, "INVALID_PRINCIPAL",
                    "authenticated principal is not a valid user ID");
        }
    }
}
