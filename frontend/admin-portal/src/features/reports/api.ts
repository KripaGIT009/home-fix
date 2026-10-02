import { apiClient } from '@api/client';
import { fileNameFromDisposition } from '@lib/download';

/**
 * Report Generation bindings (Requirement 19.2, Requirement 20).
 *
 * Admins request pre-built reports over a date range and export format. Ranges
 * of 7 days or fewer return synchronously; longer ranges are generated
 * asynchronously and delivered by email (Requirement 20.2/20.3), so a request
 * may return either a "ready" or a "queued" acknowledgement. The file itself
 * comes from the export endpoint.
 *
 * Endpoints (see design.md — Admin Service / Reporting Service):
 * - GET  /admin/reports/types    — the report types the current user may run
 * - POST /admin/reports          — request a report (acknowledgement only)
 * - POST /admin/reports/export   — the file (200), or 202 + message when queued
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
  /**
   * SYNC when the range is short enough to download now (via the export
   * endpoint); QUEUED when it is produced asynchronously and emailed. No
   * download URL is ever returned.
   */
  mode: 'SYNC' | 'QUEUED';
  /** Human-readable status message (e.g. empty-result notice, email notice). */
  message?: string | null;
}

/** What the export endpoint produced: the file, or a queued acknowledgement. */
export type ReportExport =
  { kind: 'file'; blob: Blob; fileName: string } | { kind: 'queued'; message: string };

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

/** The queued message from a 202 body, which arrives as a Blob here. */
async function queuedMessage(body: Blob): Promise<string> {
  try {
    const parsed = JSON.parse(await body.text()) as { message?: unknown };
    if (typeof parsed.message === 'string' && parsed.message) return parsed.message;
  } catch {
    // Not JSON: use the default below.
  }
  return 'Your report is being generated and will be emailed to you when ready.';
}

/**
 * POST /admin/reports/export — download the report file. A short range answers
 * 200 with the file as an attachment; a long one answers 202 with a message, as
 * the report is then generated in the background and emailed.
 */
export async function exportReport(payload: ReportRequestPayload): Promise<ReportExport> {
  const response = await apiClient.post<Blob>('/admin/reports/export', payload, {
    responseType: 'blob',
  });
  if (response.status === 202) {
    return { kind: 'queued', message: await queuedMessage(response.data) };
  }
  const disposition = response.headers['content-disposition'] as string | undefined;
  const fileName =
    fileNameFromDisposition(disposition) ??
    `report-${payload.reportTypeId}-${payload.fromDate}-to-${payload.toDate}.${payload.format.toLowerCase()}`;
  return { kind: 'file', blob: response.data, fileName };
}
