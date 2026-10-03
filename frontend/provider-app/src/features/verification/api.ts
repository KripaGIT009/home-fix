import { apiClient, isApiError } from '@api/client';

/**
 * Verification Service bindings (Requirement 5).
 *
 * The Provider App reads its own verification record and submits the required
 * documents; admins drive every later transition. See design.md — Verification
 * Service state machine.
 *
 * Endpoints (`providerId` is the provider's own user id; the service rejects
 * anyone else's):
 * - GET  /verifications/{providerId}           — current record + audit trail
 * - POST /verifications/{providerId}/documents — submit all required documents
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

/**
 * What the app shows: a server state, or NOT_STARTED when the provider has no
 * verification record yet (the service answers 404 VERIFICATION_NOT_FOUND).
 */
export type ProviderVerificationStatus = VerificationStatus | 'NOT_STARTED';

/** Required document kinds a Provider must submit (Requirement 5.3). */
export type DocumentType = 'GOVERNMENT_ID' | 'ADDRESS_PROOF' | 'SKILL_CERTIFICATION';

/** All required documents, in the order they are shown and sent. */
export const DOCUMENT_TYPES: readonly DocumentType[] = [
  'GOVERNMENT_ID',
  'ADDRESS_PROOF',
  'SKILL_CERTIFICATION',
];

export interface RequiredDocument {
  type: DocumentType;
  /** Whether a document of this type is on file. */
  submitted: boolean;
  /** When the latest document of this type was received (ISO 8601). */
  uploadedAt: string | null;
}

/** Current verification state for the signed-in Provider. */
export interface VerificationState {
  status: ProviderVerificationStatus;
  /** Documents required to progress, with submission state (Requirement 5.3). */
  requiredDocuments: RequiredDocument[];
  /** Reason recorded with the latest rejection (Requirement 5.8). */
  rejectionReason: string | null;
  /** Timestamp of the most recent status change (ISO 8601); null when not started. */
  updatedAt: string | null;
}

/** The Verification Service's read model (VerificationResponse). */
interface VerificationResponse {
  id: string;
  providerId: string;
  status: VerificationStatus;
  backgroundCheckStartedAt: string | null;
  backgroundCheckResult: string | null;
  documents: Array<{ documentType: string; storageRef: string; uploadedAt: string }>;
  auditTrail: Array<{
    sequence: number;
    fromState: string;
    toState: string;
    actorId: string;
    reason: string | null;
    createdAt: string;
  }>;
}

/** The state of a provider who has not submitted anything yet. */
export const NOT_STARTED_STATE: VerificationState = {
  status: 'NOT_STARTED',
  requiredDocuments: DOCUMENT_TYPES.map((type) => ({ type, submitted: false, uploadedAt: null })),
  rejectionReason: null,
  updatedAt: null,
};

/** Adapt the service's record to what the screens need, in this one place. */
export function toVerificationState(response: VerificationResponse): VerificationState {
  const documents = response.documents ?? [];
  const trail = [...(response.auditTrail ?? [])].sort((a, b) => b.sequence - a.sequence);
  const latestRejection = trail.find((entry) => entry.toState === 'REJECTED');

  return {
    status: response.status,
    requiredDocuments: DOCUMENT_TYPES.map((type) => {
      const uploads = documents
        .filter((doc) => doc.documentType === type)
        .map((doc) => doc.uploadedAt)
        .sort();
      return {
        type,
        submitted: uploads.length > 0,
        uploadedAt: uploads[uploads.length - 1] ?? null,
      };
    }),
    rejectionReason: latestRejection?.reason ?? null,
    updatedAt: trail[0]?.createdAt ?? null,
  };
}

/**
 * GET /verifications/{providerId} — current verification state (Requirement
 * 5.11). A provider with no record yet is "not started", not an error.
 */
export async function fetchVerificationState(providerId: string): Promise<VerificationState> {
  try {
    const { data } = await apiClient.get<VerificationResponse>(`/verifications/${providerId}`);
    return toVerificationState(data);
  } catch (error) {
    // Only the service's own not-found means "no record": a bare 404 from a
    // missing route must still surface as an error rather than pass for a
    // provider who simply has not started.
    if (isApiError(error) && error.status === 404 && error.code === 'VERIFICATION_NOT_FOUND') {
      return NOT_STARTED_STATE;
    }
    throw error;
  }
}

/**
 * POST /verifications/{providerId}/documents — submit the required documents
 * (Requirement 5.3) in one multipart request, one part per document whose part
 * name is its type. The service needs all three together (400
 * MISSING_REQUIRED_DOCUMENTS otherwise) and answers with the updated record.
 */
export async function submitVerificationDocuments(
  providerId: string,
  files: Record<DocumentType, File>,
): Promise<VerificationState> {
  const form = new FormData();
  for (const type of DOCUMENT_TYPES) {
    const file = files[type];
    form.append(type, file, file.name);
  }
  const { data } = await apiClient.post<VerificationResponse>(
    `/verifications/${providerId}/documents`,
    form,
    { headers: { 'Content-Type': 'multipart/form-data' } },
  );
  return toVerificationState(data);
}
