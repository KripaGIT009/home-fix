import type { DocumentType, VerificationState, VerificationStatus } from './api';

type Severity = 'info' | 'success' | 'warning' | 'error';

interface StatusDescriptor {
  label: string;
  description: string;
  severity: Severity;
  /** Whether this is a terminal-eligible state (Requirement 5.1). */
  terminal: boolean;
}

/**
 * The linear "happy path" through the state machine, used to render a stepper
 * (Requirement 5.1). Branch states (REJECTED, SUSPENDED) are handled outside
 * the stepper via the status descriptor + alerts.
 */
export const VERIFICATION_STEPS: {
  status: VerificationStatus;
  label: string;
}[] = [
  { status: 'PENDING', label: 'Application started' },
  { status: 'DOCUMENT_SUBMITTED', label: 'Documents submitted' },
  { status: 'DOCUMENT_VERIFIED', label: 'Documents verified' },
  { status: 'BACKGROUND_CHECK_PENDING', label: 'Background check in progress' },
  { status: 'BACKGROUND_CHECK_COMPLETED', label: 'Background check complete' },
  { status: 'APPROVED', label: 'Approved' },
];

const STATUS_MAP: Record<VerificationStatus, StatusDescriptor> = {
  PENDING: {
    label: 'Application pending',
    description: 'Your provider application has started but documents are not yet submitted.',
    severity: 'warning',
    terminal: false,
  },
  DOCUMENT_SUBMITTED: {
    label: 'Documents submitted',
    description: 'Your documents are in the review queue awaiting an admin check.',
    severity: 'info',
    terminal: false,
  },
  DOCUMENT_VERIFIED: {
    label: 'Documents verified',
    description: 'Your documents were verified. A background check will begin shortly.',
    severity: 'info',
    terminal: false,
  },
  BACKGROUND_CHECK_PENDING: {
    label: 'Background check in progress',
    description: 'Your background check is underway. No action is needed from you right now.',
    severity: 'info',
    terminal: false,
  },
  BACKGROUND_CHECK_COMPLETED: {
    label: 'Background check complete',
    description: 'Your background check is complete and awaiting final admin approval.',
    severity: 'info',
    terminal: false,
  },
  APPROVED: {
    label: 'Approved',
    description: 'You are verified and can receive job assignments.',
    severity: 'success',
    terminal: true,
  },
  REJECTED: {
    label: 'Application rejected',
    description: 'Your application was rejected. See the reason below.',
    severity: 'error',
    terminal: true,
  },
  SUSPENDED: {
    label: 'Account suspended',
    description: 'Your account is suspended and you cannot receive new jobs until reinstated.',
    severity: 'error',
    terminal: true,
  },
};

/** Resolve the display descriptor for a verification status. */
export function describeVerificationStatus(status: VerificationStatus): StatusDescriptor {
  return STATUS_MAP[status];
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

/**
 * The active step index within VERIFICATION_STEPS for a given status. Branch
 * states map to the furthest relevant point: REJECTED/SUSPENDED return -1 so
 * callers can render them outside the linear stepper.
 */
export function activeStepIndex(status: VerificationStatus): number {
  const index = VERIFICATION_STEPS.findIndex((step) => step.status === status);
  return index;
}

/**
 * Compute the required next steps a Provider must take given their current
 * verification state (Requirement 5). Most transitions are admin-driven, so
 * many states resolve to "no action needed"; PENDING and REJECTED surface a
 * concrete action, and any missing documents are listed explicitly.
 */
export function nextSteps(state: VerificationState): string[] {
  const { status, requiredDocuments } = state;
  const missing = requiredDocuments
    .filter((doc) => !doc.submitted)
    .map((doc) => documentLabel(doc.type));

  switch (status) {
    case 'PENDING':
    case 'DOCUMENT_SUBMITTED': {
      if (missing.length > 0) {
        return [`Upload the following documents: ${missing.join(', ')}.`];
      }
      return status === 'PENDING'
        ? ['Submit your required documents to start verification.']
        : ['Your documents are under review. We will notify you when the check completes.'];
    }
    case 'DOCUMENT_VERIFIED':
    case 'BACKGROUND_CHECK_PENDING':
    case 'BACKGROUND_CHECK_COMPLETED':
      return ['No action needed. Our team is reviewing your application.'];
    case 'APPROVED':
      return ['You are all set. Accept jobs from your Dashboard.'];
    case 'REJECTED':
      return ['Contact support if you believe this was a mistake or to re-apply.'];
    case 'SUSPENDED':
      return ['Contact support to understand the reason and steps to reinstate your account.'];
    default:
      return [];
  }
}
