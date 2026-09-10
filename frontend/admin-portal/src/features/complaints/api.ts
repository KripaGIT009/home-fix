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

export type ComplaintStatus = 'OPEN' | 'IN_PROGRESS' | 'RESOLVED' | 'DISPUTED';

export interface AdminComplaint {
  id: string;
  bookingReference: string;
  raisedByName: string;
  category: string;
  summary: string;
  status: ComplaintStatus;
  /** ISO 8601 SLA deadline; used to flag breaches in the UI. */
  slaDueAt: string;
  createdAt: string;
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

/** True when the complaint is unresolved and past its SLA deadline. */
export function isSlaBreached(complaint: AdminComplaint): boolean {
  if (complaint.status === 'RESOLVED') return false;
  const due = new Date(complaint.slaDueAt).getTime();
  return Number.isFinite(due) && due < Date.now();
}
