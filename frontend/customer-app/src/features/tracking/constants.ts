/** Constants for the live tracking flow (Requirement 10). */

/**
 * A location update older than this is considered stale and the tracking UI
 * shows a "last updated" indicator (Requirement 10.7). The Location Service
 * accepts at most one update every 5 s, so 60 s means ~12 missed updates.
 */
export const STALE_LOCATION_THRESHOLD_MS = 60_000;

/**
 * How often the tracking screen re-evaluates staleness and the ETA countdown.
 * A 1 s tick keeps the "last updated Ns ago" label and countdown smooth
 * without depending on new stream events.
 */
export const TRACKING_TICK_MS = 1_000;

/**
 * Booking statuses during which live location tracking is active. Tracking
 * begins at PROVIDER_ACCEPTED and the Location Service stops pushing updates
 * once the job starts (Requirement 10.5).
 */
export const TRACKABLE_STATUSES = [
  'PROVIDER_ACCEPTED',
  'PROVIDER_ON_THE_WAY',
  'PROVIDER_ARRIVED',
] as const;
