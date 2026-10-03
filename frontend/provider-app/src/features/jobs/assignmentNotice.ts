/**
 * The message the dashboard shows after the provider declines a job their
 * agency assigned (Requirement MT-6.2): the job has left their list, and this
 * says where it went. Passed as router state so it shows once, on arrival.
 */
export interface DashboardNoticeState {
  notice: string;
}

export function declinedNotice(tenantName: string | null): string {
  return `You declined the job. It has gone back to ${tenantName ?? 'your agency'} to assign to someone else.`;
}

/** Reads the notice from router state, ignoring anything else that may be there. */
export function readDashboardNotice(state: unknown): string | null {
  if (!state || typeof state !== 'object') return null;
  const { notice } = state as Partial<DashboardNoticeState>;
  return typeof notice === 'string' && notice !== '' ? notice : null;
}
