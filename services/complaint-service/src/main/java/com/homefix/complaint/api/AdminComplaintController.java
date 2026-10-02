package com.homefix.complaint.api;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.homefix.complaint.api.dto.AdminComplaintResponse;
import com.homefix.complaint.api.dto.AdminComplaintUpdateRequest;
import com.homefix.complaint.domain.ComplaintStatus;
import com.homefix.complaint.service.ComplaintService;

import jakarta.validation.Valid;

/**
 * Admin Portal complaint management (Requirement 19.2, Requirement 16). The API gateway routes
 * {@code /admin/complaints/**} here unchanged; {@code ComplaintRbacConfig} restricts it to the
 * support tier (ADMIN, SUPER_ADMIN, SUPPORT_AGENT).
 *
 * <p>Status changes are not applied here: {@link ComplaintService#adminUpdate} sends them through
 * the same transitions as the Support_Agent endpoint, so settlement holds (16.7, 16.8), the
 * {@code ComplaintStatusChanged} event and the customer notification (16.3) all follow.
 *
 * <p>Parameter names are spelled out because the build does not compile with {@code -parameters}.
 */
@RestController
@RequestMapping("/admin/complaints")
public class AdminComplaintController {

    private final ComplaintService complaintService;

    public AdminComplaintController(ComplaintService complaintService) {
        this.complaintService = complaintService;
    }

    /**
     * Complaints newest first, optionally narrowed by a case-insensitive {@code search} (description
     * or complaint/booking/customer id) and one {@code status}. A bare array bounded server-side;
     * the portal does not page.
     */
    @GetMapping
    public List<AdminComplaintResponse> list(
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "status", required = false) ComplaintStatus status) {
        return complaintService.searchForAdmin(search, status).stream()
                .map(AdminComplaintResponse::from)
                .toList();
    }

    /** Changes a complaint's status and/or records a resolution note (required on RESOLVED). */
    @PatchMapping("/{complaintId}")
    public AdminComplaintResponse update(@PathVariable("complaintId") UUID complaintId,
                                         @Valid @RequestBody AdminComplaintUpdateRequest req) {
        return AdminComplaintResponse.from(
                complaintService.adminUpdate(complaintId, req.status(), req.resolutionNote()));
    }
}
