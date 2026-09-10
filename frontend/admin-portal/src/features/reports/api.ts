import { apiClient } from '@api/client';

/**
 * Report Generation bindings (Requirement 19.2, Requirement 20).
 *
 * Admins request pre-built reports over a date range and export format. Ranges
 * of 7 days or fewer return synchronously; longer ranges are generated
 * asynchronously and delivered by email (Requirement 20.2/20.3), so a request
 * may return either a ready download link or a "queued" acknowledgement.
 *
 * Endpoints (see design.md — Admin Service / Reporting Service):
 * - GET  /admin/reports/types    — available report types for the current role
 * - POST /admin/reports          — request a report
 */

export type ReportFormat = 'PDF' | 'CSV';

export interface ReportType {
  id: string;
  name: string;
  /** True when only Finance_Admin may run it (Requirement 20.6). */
  financeOnly: boolean;
}

export interface ReportRequestPayload {
  reportTypeId: string;
  fromDate: string;
  toDate: string;
  format: ReportFormat;
}

export interface ReportResult {
  /** SYNC when generated inline; QUEUED when produced asynchronously. */
  mode: 'SYNC' | 'QUEUED';
  /** Present when mode is SYNC: a short-lived signed download URL. */
  downloadUrl?: string;
  /** Human-readable status message (e.g. empty-result notice, email notice). */
  message: string;
}

/** GET /admin/reports/types — report types available to the current user. */
export async function fetchReportTypes(): Promise<ReportType[]> {
  const { data } = await apiClient.get<ReportType[]>('/admin/reports/types');
  return data;
}

/** POST /admin/reports — request a report; sync or queued per date range. */
export async function requestReport(payload: ReportRequestPayload): Promise<ReportResult> {
  const { data } = await apiClient.post<ReportResult>('/admin/reports', payload);
  return data;
}
