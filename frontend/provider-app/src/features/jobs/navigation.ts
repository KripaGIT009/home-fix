/**
 * Build a navigation deep-link to a set of coordinates (Requirement 11.1).
 *
 * Uses the platform-agnostic `geo:` URI with a Google Maps HTTPS fallback in
 * the query so it resolves on both native apps and desktop browsers.
 */
export function buildNavigationDeepLink(latitude: number, longitude: number): string {
  const lat = latitude.toFixed(6);
  const lng = longitude.toFixed(6);
  return `https://www.google.com/maps/dir/?api=1&destination=${lat},${lng}`;
}
