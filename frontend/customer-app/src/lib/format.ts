/**
 * Formatting helpers shared across screens.
 */

/**
 * Format a numeric amount as currency. Defaults to INR since HomeFix is an
 * India-first platform, but honours whatever currency the backend returns.
 */
export function formatCurrency(amount: number, currency = 'INR'): string {
  try {
    return new Intl.NumberFormat('en-IN', {
      style: 'currency',
      currency,
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(amount);
  } catch {
    // Fall back gracefully if an unknown currency code is supplied.
    return `${currency} ${amount.toFixed(2)}`;
  }
}

/** Format a distance in kilometers, e.g. "1.2 km" or "850 m" for short hops. */
export function formatDistanceKm(km: number): string {
  if (!Number.isFinite(km) || km < 0) return '—';
  if (km < 1) {
    return `${Math.round(km * 1000)} m`;
  }
  return `${km.toFixed(1)} km`;
}

/** Format an ETA in whole minutes, e.g. "5 min" or "Arriving" when ≤ 0. */
export function formatEtaMinutes(minutes: number): string {
  if (!Number.isFinite(minutes) || minutes <= 0) return 'Arriving';
  return `${Math.round(minutes)} min`;
}

/** Format an absolute date, e.g. "12 Feb 2025". */
export function formatDate(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return new Intl.DateTimeFormat('en-IN', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  }).format(date);
}

/** Format a date with time, e.g. "12 Feb 2025, 3:45 pm". */
export function formatDateTime(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return new Intl.DateTimeFormat('en-IN', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  }).format(date);
}

/**
 * Format a short "time ago" label from an ISO timestamp relative to `now`,
 * e.g. "just now", "12s ago", "3m ago". Used for location staleness indicators.
 */
export function formatTimeAgo(iso: string, now: number = Date.now()): string {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return 'unknown';
  const seconds = Math.max(0, Math.round((now - then) / 1000));
  if (seconds < 5) return 'just now';
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  return `${hours}h ago`;
}

/**
 * Format a headline price: whole rupees drop the paise ("₹299"), fractional
 * amounts keep them ("₹299.50"). Used for "from ₹…" prices on cards; bills and
 * breakdowns keep {@link formatCurrency}'s fixed two decimals.
 */
export function formatPrice(amount: number, currency = 'INR'): string {
  const whole = Number.isInteger(amount);
  try {
    return new Intl.NumberFormat('en-IN', {
      style: 'currency',
      currency,
      minimumFractionDigits: whole ? 0 : 2,
      maximumFractionDigits: whole ? 0 : 2,
    }).format(amount);
  } catch {
    return `${currency} ${whole ? amount : amount.toFixed(2)}`;
  }
}

/** Format a duration in minutes, e.g. "45 min" or "1 hr 30 min". */
export function formatDuration(minutes: number): string {
  if (!Number.isFinite(minutes) || minutes <= 0) return '—';
  if (minutes < 60) return `${Math.round(minutes)} min`;
  const hours = Math.floor(minutes / 60);
  const rest = Math.round(minutes % 60);
  return rest ? `${hours} hr ${rest} min` : `${hours} hr`;
}
