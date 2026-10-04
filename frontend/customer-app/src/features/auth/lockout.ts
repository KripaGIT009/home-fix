import { isApiError, type ApiError } from '@api/client';
import { OTP_LOCKED_CODE } from './constants';

/**
 * Lockout handling (Requirement 1.3): after 5+ consecutive incorrect OTPs the
 * Auth Service locks the session for 30 minutes and returns an error that
 * indicates the lockout duration.
 *
 * The normalized ApiError only carries a message string, so we defensively
 * parse the remaining lockout seconds/minutes from that message. When no
 * duration can be parsed we still surface the lockout via the error code.
 */

export interface LockoutInfo {
  /** Whether the error represents an OTP session lockout. */
  isLocked: boolean;
  /** Remaining lockout time in seconds, when it could be determined. */
  remainingSeconds?: number;
  /** Human-readable message to display. */
  message: string;
}

/** Parse a lockout duration (in seconds) out of a free-text message. */
function parseRemainingSeconds(message: string): number | undefined {
  const minutesMatch = message.match(/(\d+)\s*minute/i);
  if (minutesMatch?.[1]) {
    return Number(minutesMatch[1]) * 60;
  }
  const secondsMatch = message.match(/(\d+)\s*second/i);
  if (secondsMatch?.[1]) {
    return Number(secondsMatch[1]);
  }
  return undefined;
}

/** Inspect an unknown error and describe any OTP lockout it represents. */
export function getLockoutInfo(error: unknown): LockoutInfo {
  if (!isApiError(error)) {
    return { isLocked: false, message: 'Something went wrong. Please try again.' };
  }

  const isLocked = error.code === OTP_LOCKED_CODE || error.status === 429;
  if (!isLocked) {
    return { isLocked: false, message: error.message };
  }

  const remainingSeconds = parseRemainingSeconds(error.message);
  return remainingSeconds !== undefined
    ? { isLocked: true, remainingSeconds, message: error.message }
    : { isLocked: true, message: error.message };
}

/**
 * Seconds a 429 asks the caller to wait (a password sign-in lockout, a resend
 * limit): the `Retry-After` header when it was readable, else the duration the
 * message names, else undefined.
 */
export function getRetryAfterSeconds(error: ApiError): number | undefined {
  return error.retryAfterSeconds ?? parseRemainingSeconds(error.message);
}

/** Format a duration in seconds as `M:SS` for countdown display. */
export function formatDuration(totalSeconds: number): string {
  const safe = Math.max(0, Math.floor(totalSeconds));
  const minutes = Math.floor(safe / 60);
  const seconds = safe % 60;
  return `${minutes}:${seconds.toString().padStart(2, '0')}`;
}
