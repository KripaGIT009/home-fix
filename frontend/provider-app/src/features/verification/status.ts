import { isApiError } from '@api/client';
import type { DocumentType, ProviderVerificationStatus, VerificationStatus } from './api';

type Severity = 'info' | 'success' | 'warning' | 'error';

export interface StatusDescriptor {
  label: string;
  /** What the state means, in plain language. */
  description: string;
  /** What happens next, and whether the provider has to do anything. */
  next: string;
  severity: Severity;
}

/**
 * The linear "happy path" through the state machine, used to render a stepper
 * (Requirement 5.1), each step with a one-line explanation. Branch states
 * (REJECTED, SUSPENDED) are handled outside the stepper.
 */
export const VERIFICATION_STEPS: {
  status: VerificationStatus;
  label: string;
  caption: string;
}[] = [
  {
    status: 'PENDING',
    label: 'Send your documents',
    caption: 'ID, address proof and a skill certificate',
  },
  {
    status: 'DOCUMENT_SUBMITTED',
    label: 'Document review',
    caption: 'A HomeFix admin checks what you sent',
  },
  {
    status: 'DOCUMENT_VERIFIED',
    label: 'Documents accepted',
    caption: 'Your background check is started for you',
  },
  {
    status: 'BACKGROUND_CHECK_PENDING',
    label: 'Background check',
    caption: 'An identity and record check runs',
  },
  {
    status: 'BACKGROUND_CHECK_COMPLETED',
    label: 'Final review',
    caption: 'An admin makes the final decision',
  },
  { status: 'APPROVED', label: 'Verified', caption: 'You can receive job requests' },
];

const STATUS_MAP: Record<ProviderVerificationStatus, StatusDescriptor> = {
  NOT_STARTED: {
    label: 'Not started',
    description:
      'You have not sent your documents yet. HomeFix can only send you jobs once you are verified.',
    next: 'Upload the three documents below. A HomeFix admin reviews them after you send them.',
    severity: 'warning',
  },
  PENDING: {
    label: 'Documents needed',
    description: 'Your application is open, but we still need your documents.',
    next: 'Upload the three documents below. A HomeFix admin reviews them after you send them.',
    severity: 'warning',
  },
  DOCUMENT_SUBMITTED: {
    label: 'Under review',
    description: 'We have your documents. They are waiting for a HomeFix admin to check them.',
    next: 'Nothing to do for now. Once your documents are accepted, your background check starts automatically.',
    severity: 'info',
  },
  DOCUMENT_VERIFIED: {
    label: 'Documents accepted',
    description: 'Your documents passed the review.',
    next: 'Nothing to do. Your background check is being started for you.',
    severity: 'info',
  },
  BACKGROUND_CHECK_PENDING: {
    label: 'Background check in progress',
    description: 'A check of your identity and record is under way.',
    next: 'Nothing to do. When the check finishes, an admin makes the final decision.',
    severity: 'info',
  },
  BACKGROUND_CHECK_COMPLETED: {
    label: 'Final review',
    description: 'Your background check is done and an admin is making the final decision.',
    next: 'Nothing to do. Once you are approved you can start receiving jobs.',
    severity: 'info',
  },
  APPROVED: {
    label: 'Verified',
    description: 'You are verified. HomeFix can send you job requests.',
    next: 'You are all set. Accept jobs from your Dashboard.',
    severity: 'success',
  },
  REJECTED: {
    label: 'Not approved',
    description: 'Your application was not approved. The reason is shown below.',
    next: 'A rejection is final. Contact HomeFix support if you think it is a mistake or your circumstances have changed.',
    severity: 'error',
  },
  SUSPENDED: {
    label: 'Suspended',
    description: 'Your account is paused, so you will not receive new jobs.',
    next: 'Contact support to find out why and how to be reinstated.',
    severity: 'error',
  },
};

/** Resolve the display descriptor for a verification status. */
export function describeVerificationStatus(status: ProviderVerificationStatus): StatusDescriptor {
  return STATUS_MAP[status];
}

/**
 * Whether the provider can submit documents in this state. A rejection is final in the
 * Verification Service (Requirement 5.8): a resubmission is refused, so a rejected provider is
 * pointed to support rather than offered a form that cannot succeed.
 */
export function canUploadDocuments(status: ProviderVerificationStatus): boolean {
  return status === 'NOT_STARTED' || status === 'PENDING';
}

/** Human-readable label for a required document type (Requirement 5.3). */
export function documentLabel(type: DocumentType): string {
  switch (type) {
    case 'GOVERNMENT_ID':
      return 'Government ID';
    case 'ADDRESS_PROOF':
      return 'Address proof';
    case 'SKILL_CERTIFICATION':
      return 'Skill certification';
    default:
      return type;
  }
}

/** What counts as each document, so the provider picks the right file. */
export function documentHint(type: DocumentType): string {
  switch (type) {
    case 'GOVERNMENT_ID':
      return 'Aadhaar, PAN card, passport, voter ID or driving licence';
    case 'ADDRESS_PROOF':
      return 'A recent utility bill, bank statement or rental agreement';
    case 'SKILL_CERTIFICATION':
      return 'A trade certificate, ITI diploma or training certificate';
    default:
      return '';
  }
}

/**
 * The active step index within VERIFICATION_STEPS. NOT_STARTED sits on the
 * first step; REJECTED/SUSPENDED return -1 so callers render them outside the
 * linear stepper.
 */
export function activeStepIndex(status: ProviderVerificationStatus): number {
  if (status === 'NOT_STARTED') return 0;
  return VERIFICATION_STEPS.findIndex((step) => step.status === status);
}

/** Plain-language message for a failed document submission. */
export function uploadErrorMessage(error: unknown): string {
  if (!isApiError(error)) return 'Could not send your documents. Try again.';
  if (error.status === 413) {
    return 'One of the files is too large. Use a smaller photo or a compressed PDF and try again.';
  }
  if (error.code === 'MISSING_REQUIRED_DOCUMENTS') {
    return 'All three documents are needed together. Choose a file for each one and try again.';
  }
  if (error.code === 'INVALID_STATE_TRANSITION') {
    return 'Your application cannot take new documents in its current state. Contact support for help.';
  }
  return error.message;
}
