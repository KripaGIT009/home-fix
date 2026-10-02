package com.homefix.complaint.api.dto;

import com.homefix.complaint.domain.ComplaintStatus;

import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code PATCH /admin/complaints/{id}} ({@code ComplaintUpdatePayload} in the Admin
 * Portal, Requirement 19.2). The note's length and its being required on RESOLVED are checked by
 * {@code ComplaintService.adminUpdate}, which owns the rule.
 */
public record AdminComplaintUpdateRequest(@NotNull ComplaintStatus status, String resolutionNote) {
}
