import { apiClient } from '@api/client';

/**
 * Complaint Management bindings (Requirement 19.2, Requirement 13).
 *
 * Support agents and admins triage complaints, add resolution notes, and
 * transition them through the lifecycle. Actions are recorded in the Audit_Log.
 *
 * Endpoints (see design.md — Admin Service / Complaint Service):
 * - GET   /admin/complaints              — list/search complaints (status filter)
 * - PATCH /admin/complaints/{id}         — update status / add resolution note
 */

/**
 * The Complaint Service lifecycle (Requirement 16). ESCALATED (SLA breach,
 * 16.4) and REFUND_FAILED (payment rejected the refund, 16.6) are set by the
 * system, never chosen by an agent; RESOLVED and CLOSED are terminal.
 */
export type ComplaintStatus =
  'OPEN' | 'IN_PROGRESS' | 'ESCALATED' | 'DISPUTED' | 'REFUND_FAILED' | 'RESOLVED' | 'CLOSED';

/** Every status, in lifecycle order, for the filter. */
export const COMPLAINT_STATUSES: ReadonlyArray<ComplaintStatus> = [
  'OPEN',
  'IN_PROGRESS',
  'ESCALATED',
  'DISPUTED',
  'REFUND_FAILED',
  'RESOLVED',
  'CLOSED',
];

/** Terminal statuses: the Complaint Service refuses any change out of them. */
export function isTerminalComplaint(status: ComplaintStatus): boolean {
  return status === 'RESOLVED' || status === 'CLOSED';
}

/**
 * The statuses an agent may move a complaint to from its current one, mirroring
 * ComplaintService.changeStatus:
 * - nothing leaves RESOLVED or CLOSED;
 * - ESCALATED and REFUND_FAILED are system-set, so they are never offered;
 * - DISPUTED holds the provider's settlement until the complaint finishes, so
 *   it only moves on to RESOLVED or CLOSED (which release the hold);
 * - OPEN is where a complaint starts, not somewhere to send it back to.
 */
export function complaintTransitions(current: ComplaintStatus): ComplaintStatus[] {
  switch (current) {
    case 'RESOLVED':
    case 'CLOSED':
      return [];
    case 'DISPUTED':
      return ['RESOLVED', 'CLOSED'];
    case 'IN_PROGRESS':
      return ['DISPUTED', 'RESOLVED', 'CLOSED'];
    case 'OPEN':
    case 'ESCALATED':
    case 'REFUND_FAILED':
      return ['IN_PROGRESS', 'DISPUTED', 'RESOLVED', 'CLOSED'];
  }
}

/**
 * A complaint row. The Complaint Service owns the complaint; the booking
 * reference and the complainant's name live in other services and may come
 * back null or absent.
 */
export interface AdminComplaint {
  id: string;
  bookingReference?: string | null;
  raisedByName?: string | null;
  category: string;
  summary: string;
  status: ComplaintStatus;
  /** ISO 8601 SLA deadline; used to flag breaches in the UI. */
  slaDueAt: string;
  createdAt: string;
  /** The latest resolution note staff recorded; null when there is none. */
  resolutionNote?: string | null;
}

export interface ComplaintUpdatePayload {
  status: ComplaintStatus;
  resolutionNote?: string;
}

/** GET /admin/complaints — complaints, optionally filtered by search + status. */
export async function fetchComplaints(params: {
  search?: string;
  status?: ComplaintStatus | '';
}): Promise<AdminComplaint[]> {
  const query: Record<string, string> = {};
  if (params.search) query.search = params.search;
  if (params.status) query.status = params.status;
  const { data } = await apiClient.get<AdminComplaint[]>('/admin/complaints', {
    params: Object.keys(query).length > 0 ? query : undefined,
  });
  return data;
}

/** PATCH /admin/complaints/{id} — update status / add a resolution note. */
export async function updateComplaint(
  id: string,
  payload: ComplaintUpdatePayload,
): Promise<AdminComplaint> {
  const { data } = await apiClient.patch<AdminComplaint>(`/admin/complaints/${id}`, payload);
  return data;
}

/** True when the complaint is still open and past its SLA deadline. */
export function isSlaBreached(complaint: AdminComplaint): boolean {
  if (isTerminalComplaint(complaint.status)) return false;
  const due = new Date(complaint.slaDueAt).getTime();
  return Number.isFinite(due) && due < Date.now();
}
