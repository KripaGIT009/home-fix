package com.homefix.complaint.service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A Support_Agent's approval of a refund on a complaint (Requirement 16.5).
 *
 * @param complaintId    the complaint being refunded
 * @param amount         the refund amount; positive, at most two decimal places
 * @param reason         the agent's justification, recorded for audit (optional, up to 500 chars)
 * @param approvedBy     the approving Support_Agent, taken from the JWT subject, never the body
 * @param idempotencyKey optional client key (up to 64 chars); a retry carrying the same key and
 *                       amount replays the recorded outcome instead of being refused
 */
public record ApproveRefundCommand(UUID complaintId, BigDecimal amount, String reason,
                                   UUID approvedBy, String idempotencyKey) {
}
