import type { JobStatus } from './api';

type StatusColor = 'default' | 'info' | 'primary' | 'success' | 'warning' | 'error';

interface StatusDescriptor {
  label: string;
  color: StatusColor;
}

/**
 * Presentation metadata for each job status, driving the coloured status
 * indicator (MUI Chip) on the active job list. Colours progress from neutral
 * (assigned) to primary (in progress) to success (completed).
 */
const STATUS_MAP: Record<JobStatus, StatusDescriptor> = {
  ASSIGNED: { label: 'Assigned', color: 'default' },
  ACCEPTED: { label: 'Accepted', color: 'info' },
  EN_ROUTE: { label: 'En route', color: 'info' },
  ARRIVED: { label: 'Arrived', color: 'warning' },
  IN_PROGRESS: { label: 'In progress', color: 'primary' },
  COMPLETED: { label: 'Completed', color: 'success' },
  CANCELLED: { label: 'Cancelled', color: 'error' },
};

/** Resolve display label + colour for a job status, with a safe fallback. */
export function describeJobStatus(status: JobStatus): StatusDescriptor {
  return STATUS_MAP[status] ?? { label: status, color: 'default' };
}
