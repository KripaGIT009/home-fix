import { apiClient } from '@api/client';

/**
 * Verification Service bindings (Requirement 5).
 *
 * The Provider App only *reads* the current verification state and its audit
 * trail here; admins drive transitions. See design.md — Verification Service
 * state machine.
 *
 * Endpoint:
 * - GET /verification/me — current verification status + document checklist
 */

/**
 * Provider verification states and permitted transitions (Requirement 5.1):
 *   PENDING → DOCUMENT_SUBMITTED → DOCUMENT_VERIFIED → BACKGROUND_CHECK_PENDING
 *           → BACKGROUND_CHECK_COMPLETED → APPROVED → SUSPENDED → APPROVED
 *                                        → REJECTED
 * APPROVED, REJECTED, SUSPENDED are the only terminal-eligible states.
 */
export type VerificationStatus =
  | 'PENDING'
  | 'DOCUMENT_SUBMITTED'
  | 'DOCUMENT_VERIFIED'
  | 'BACKGROUND_CHECK_PENDING'
  | 'BACKGROUND_CHECK_COMPLETED'
  | 'APPROVED'
  | 'REJECTED'
  | 'SUSPENDED';

/** Required document kinds a Provider must submit (Requirement 5.3). */
export type DocumentType = 'GOVERNMENT_ID' | 'ADDRESS_PROOF' | 'SKILL_CERTIFICATION';

export interface RequiredDocument {
  type: DocumentType;
  /** Whether this document has been uploaded/received. */
  submitted: boolean;
}

/** Current verification state for the authenticated Provider. */
export interface VerificationState {
  status: VerificationStatus;
  /** Documents required to progress, with submission state (Requirement 5.3). */
  requiredDocuments: RequiredDocument[];
  /** Reason recorded when the Provider was REJECTED (Requirement 5.8). */
  rejectionReason: string | null;
  /** Timestamp of the most recent status change (ISO 8601). */
  updatedAt: string;
}

/** GET /verification/me — current verification state (Requirement 5.11). */
export async function fetchVerificationState(): Promise<VerificationState> {
  const { data } = await apiClient.get<VerificationState>('/verification/me');
  return data;
}
