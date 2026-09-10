/** Default country calling code for HomeFix's India-first launch market. */
export const DEFAULT_COUNTRY_CODE = '+91';

/**
 * Normalizes a mobile number the customer typed into the E.164 form the Auth
 * Service requires (`^\+[1-9]\d{7,14}$` on `/auth/register/otp` and
 * `/auth/register/verify`).
 *
 * The form accepts a bare 10-digit number — that is how people write their own
 * number — so without this the request is rejected with a 400 before an OTP is
 * ever sent. Numbers already carrying a `+` prefix are passed through, and a
 * leading `0` (the domestic trunk prefix) is dropped.
 */
export function toE164(mobileNumber: string, countryCode = DEFAULT_COUNTRY_CODE): string {
  const trimmed = mobileNumber.trim().replace(/[\s()-]/g, '');
  if (trimmed.startsWith('+')) {
    return trimmed;
  }
  const digits = trimmed.replace(/\D/g, '').replace(/^0+/, '');
  return `${countryCode}${digits}`;
}

/** Renders an E.164 number for display, e.g. "+91 98765 43210". */
export function formatMobileNumber(e164: string): string {
  const match = /^(\+\d{1,3})(\d{5})(\d{5})$/.exec(e164);
  return match ? `${match[1]} ${match[2]} ${match[3]}` : e164;
}
