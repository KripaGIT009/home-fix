/**
 * Time-in-queue helpers for the Requests screen (Requirement MT-5.1 "time
 * queued"). A queued request is released as unavailable after a configurable
 * timeout (default 60 min, Requirement MT-7.1), so how long it has waited is
 * the admin's main cue for what to assign first.
 */

/** Minutes after which a wait is shown as getting long, then as urgent. */
const LONG_WAIT_MINUTES = 15;
const URGENT_WAIT_MINUTES = 40;

/** Whole minutes elapsed since `fromIso`, never negative (clock skew). */
export function minutesSince(fromIso: string, now: number): number {
  const from = new Date(fromIso).getTime();
  if (Number.isNaN(from)) return 0;
  return Math.max(0, Math.floor((now - from) / 60_000));
}

/** "just now", "12 min", "1 h 05 min". */
export function formatWait(minutes: number): string {
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${minutes} min`;
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return `${hours} h ${String(rest).padStart(2, '0')} min`;
}

/** Chip colour for a wait: neutral, then amber, then red. */
export function waitColor(minutes: number): 'default' | 'warning' | 'error' {
  if (minutes >= URGENT_WAIT_MINUTES) return 'error';
  if (minutes >= LONG_WAIT_MINUTES) return 'warning';
  return 'default';
}
