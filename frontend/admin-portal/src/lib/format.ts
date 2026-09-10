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

/** Format an integer with thousands separators, e.g. "1,240". */
export function formatNumber(value: number): string {
  return new Intl.NumberFormat('en-IN').format(value);
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
