import { apiClient } from '@api/client';

/**
 * Review Moderation bindings (Requirement 19.2, Requirement 12).
 *
 * The Rating & Review Service flags reviews suspected of fraud or abuse. Admins
 * moderate flagged reviews — publishing or removing them. Removing a review
 * triggers a weighted aggregate recalculation server-side.
 *
 * Endpoints (see design.md — Admin Service / Rating & Review Service):
 * - GET   /admin/reviews                  — list reviews (moderation status filter)
 * - POST  /admin/reviews/{id}/moderate    — approve (publish) or remove a review
 */

export type ModerationStatus = 'PENDING' | 'FLAGGED' | 'PUBLISHED' | 'REMOVED';

/**
 * A review row. The Rating & Review Service owns the review; the booking
 * reference and the reviewer's and provider's names live in other services and
 * may come back null or absent, as may the flag reason and an empty comment.
 */
export interface AdminReview {
  id: string;
  bookingReference?: string | null;
  reviewerName?: string | null;
  providerName?: string | null;
  rating: number;
  comment?: string | null;
  status: ModerationStatus;
  /** Reason the review was auto-flagged, when applicable. */
  flagReason?: string | null;
  createdAt: string;
}

export type ModerationAction = 'PUBLISH' | 'REMOVE';

export interface ModeratePayload {
  action: ModerationAction;
  /** Required when removing: the moderation reason. */
  reason?: string;
}

/** GET /admin/reviews — reviews, optionally filtered by moderation status. */
export async function fetchReviews(status?: ModerationStatus | ''): Promise<AdminReview[]> {
  const { data } = await apiClient.get<AdminReview[]>('/admin/reviews', {
    params: status ? { status } : undefined,
  });
  return data;
}

/** POST /admin/reviews/{id}/moderate — publish or remove a review. */
export async function moderateReview(id: string, payload: ModeratePayload): Promise<AdminReview> {
  const { data } = await apiClient.post<AdminReview>(`/admin/reviews/${id}/moderate`, payload);
  return data;
}
