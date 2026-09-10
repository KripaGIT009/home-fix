import { apiClient } from '@api/client';

/**
 * Verification Queue bindings (Requirement 19.3). The Admin Service surfaces
 * providers awaiting document review and proxies inline document viewing.
 *
 * Endpoints (see design.md — Admin Service / Verification Service):
 * - GET  /admin/verification/queue          — providers in DOCUMENT_SUBMITTED
 * - GET  /admin/verification/{id}/documents — a provider's submitted documents
 * - POST /admin/verification/{id}/decision  — approve/reject the submission
 */

/** A submitted document reference for inline viewing (Requirement 19.3). */
export interface VerificationDocument {
  id: string;
  /** Human label, e.g. "Aadhaar", "PAN card", "Trade certificate". */
  type: string;
  /** MIME type, used to choose an inline renderer (PDF vs image). */
  contentType: string;
  /**
   * URL the browser can render inline. The Admin Service returns a short-lived,
   * pre-signed URL so the document viewer can embed it without a separate
   * download step (Requirement 19.3).
   */
  url: string;
  fileName: string;
}

/** A provider awaiting verification review. */
export interface VerificationQueueEntry {
  providerId: string;
  displayName: string;
  mobileNumber: string;
  /** Primary skill/trade the provider registered under. */
  primarySkill: string;
  /** ISO 8601 submission timestamp; the queue is sorted by this, oldest first. */
  submittedAt: string;
  documentCount: number;
}

export type VerificationDecision = 'APPROVE' | 'REJECT';

export interface DecisionPayload {
  decision: VerificationDecision;
  /** Required when rejecting: the reason shown to the provider. */
  reason?: string;
}

/**
 * GET /admin/verification/queue — providers in DOCUMENT_SUBMITTED status.
 *
 * The Admin Service returns the queue sorted by submission date oldest-first
 * (Requirement 19.3); we defensively re-sort on the client so display order is
 * guaranteed regardless of backend ordering.
 */
export async function fetchVerificationQueue(): Promise<VerificationQueueEntry[]> {
  const { data } = await apiClient.get<VerificationQueueEntry[]>('/admin/verification/queue');
  return [...data].sort(
    (a, b) => new Date(a.submittedAt).getTime() - new Date(b.submittedAt).getTime(),
  );
}

/** GET /admin/verification/{id}/documents — a provider's submitted documents. */
export async function fetchVerificationDocuments(
  providerId: string,
): Promise<VerificationDocument[]> {
  const { data } = await apiClient.get<VerificationDocument[]>(
    `/admin/verification/${providerId}/documents`,
  );
  return data;
}

/** POST /admin/verification/{id}/decision — approve or reject the submission. */
export async function submitVerificationDecision(
  providerId: string,
  payload: DecisionPayload,
): Promise<void> {
  await apiClient.post(`/admin/verification/${providerId}/decision`, payload);
}
