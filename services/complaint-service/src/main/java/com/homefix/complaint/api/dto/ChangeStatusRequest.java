package com.homefix.complaint.api.dto;

import com.homefix.complaint.domain.ComplaintStatus;

import jakarta.validation.constraints.NotNull;

/** Request body for a Support_Agent status change on a complaint (Requirement 16.3). */
public record ChangeStatusRequest(@NotNull ComplaintStatus status) {
}
