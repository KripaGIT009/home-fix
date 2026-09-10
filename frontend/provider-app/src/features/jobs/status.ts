import type { JobExecutionStatus } from './api';

type StatusColor = 'default' | 'info' | 'primary' | 'success' | 'warning' | 'error';

interface StatusDescriptor {
  label: string;
  color: StatusColor;
}

/**
 * Presentation metadata for each job-execution status, driving the coloured
 * status indicator (MUI Chip) on the job screens. Mirrors the dashboard status
 * map but covers the full Provider-facing state set (Requirement 9.1).
 */
const STATUS_MAP: Record<JobExecutionStatus, StatusDescriptor> = {
  PROVIDER_ASSIGNED: { label: 'Assigned', color: 'default' },
  PROVIDER_ACCEPTED: { label: 'Accepted', color: 'info' },
  PROVIDER_ON_THE_WAY: { label: 'On the way', color: 'info' },
  PROVIDER_ARRIVED: { label: 'Arrived', color: 'warning' },
  JOB_STARTED: { label: 'In progress', color: 'primary' },
  JOB_PAUSED: { label: 'Paused', color: 'warning' },
  ADDITIONAL_QUOTE_REQUIRED: { label: 'Quote requested', color: 'warning' },
  CUSTOMER_APPROVAL_PENDING: { label: 'Awaiting approval', color: 'warning' },
  JOB_COMPLETED: { label: 'Completed', color: 'success' },
  CUSTOMER_CONFIRMED: { label: 'Confirmed', color: 'success' },
  PAYMENT_PENDING: { label: 'Payment pending', color: 'info' },
  PAYMENT_COMPLETED: { label: 'Paid', color: 'success' },
  CANCELLED: { label: 'Cancelled', color: 'error' },
};

/** Resolve display label + colour for a job status, with a safe fallback. */
export function describeJobStatus(status: JobExecutionStatus): StatusDescriptor {
  return STATUS_MAP[status] ?? { label: status, color: 'default' };
}

/** Whether the job is actively running (started, not paused). */
export function isJobRunning(status: JobExecutionStatus): boolean {
  return status === 'JOB_STARTED';
}

/** Whether the job is before the on-site "arrived" milestone. */
export function isBeforeArrival(status: JobExecutionStatus): boolean {
  return (
    status === 'PROVIDER_ASSIGNED' ||
    status === 'PROVIDER_ACCEPTED' ||
    status === 'PROVIDER_ON_THE_WAY'
  );
}
