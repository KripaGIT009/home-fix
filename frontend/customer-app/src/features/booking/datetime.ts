import { MAX_SCHEDULE_HORIZON_MS, MIN_LEAD_TIME_MS } from './constants';

/**
 * Datetime helpers for the scheduled-booking picker (Requirements 7.7, 7.8).
 *
 * `<input type="datetime-local">` expects/produces values in the form
 * `YYYY-MM-DDTHH:mm` in local time (no timezone). These helpers convert a
 * Date to that local string and compute the min/max bounds for the picker.
 */

/** Format a Date as a `datetime-local`-compatible local string. */
export function toDateTimeLocalValue(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  const year = date.getFullYear();
  const month = pad(date.getMonth() + 1);
  const day = pad(date.getDate());
  const hours = pad(date.getHours());
  const minutes = pad(date.getMinutes());
  return `${year}-${month}-${day}T${hours}:${minutes}`;
}

/** Earliest selectable time: now + 2h minimum lead time (Requirement 7.7). */
export function minScheduleValue(now: Date = new Date()): string {
  return toDateTimeLocalValue(new Date(now.getTime() + MIN_LEAD_TIME_MS));
}

/** Latest selectable time: now + 90 days horizon (Requirement 7.8). */
export function maxScheduleValue(now: Date = new Date()): string {
  return toDateTimeLocalValue(new Date(now.getTime() + MAX_SCHEDULE_HORIZON_MS));
}

/**
 * Convert a `datetime-local` value to an ISO 8601 string (UTC) for the API.
 * Returns an empty string if the input is empty/invalid.
 */
export function toIsoString(dateTimeLocal: string): string {
  if (!dateTimeLocal) return '';
  const date = new Date(dateTimeLocal);
  return Number.isNaN(date.getTime()) ? '' : date.toISOString();
}
