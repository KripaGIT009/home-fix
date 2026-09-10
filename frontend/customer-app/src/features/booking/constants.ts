/** Booking creation constants (Requirements 7 and 8). */

/**
 * Minimum lead time for a scheduled booking: the requested time must be at
 * least 2 hours from now (Requirement 7.7).
 */
export const MIN_LEAD_TIME_MS = 2 * 60 * 60 * 1000;

/**
 * Maximum scheduling horizon: the requested time must be no more than 90 days
 * from now (Requirement 7.8).
 */
export const MAX_SCHEDULE_HORIZON_DAYS = 90;
export const MAX_SCHEDULE_HORIZON_MS = MAX_SCHEDULE_HORIZON_DAYS * 24 * 60 * 60 * 1000;

/** Media upload limits at booking creation (Requirement 7.2). */
export const MAX_MEDIA_FILES = 10;
export const MAX_MEDIA_FILE_BYTES = 50 * 1024 * 1024; // 50 MB
export const MAX_MEDIA_FILE_MB = 50;

/** Accepted media MIME types (JPEG, PNG, MP4, MOV — Requirement 7.2). */
export const ACCEPTED_MEDIA_MIME_TYPES = [
  'image/jpeg',
  'image/png',
  'video/mp4',
  'video/quicktime', // .mov
] as const;

/** The `accept` attribute string for the media file input. */
export const MEDIA_ACCEPT_ATTR =
  '.jpg,.jpeg,.png,.mp4,.mov,image/jpeg,image/png,video/mp4,video/quicktime';

/** Human-readable list of accepted formats for helper text. */
export const ACCEPTED_MEDIA_LABEL = 'JPEG, PNG, MP4, or MOV';

/** Maximum description length for a service request. */
export const MAX_DESCRIPTION_LENGTH = 1000;
