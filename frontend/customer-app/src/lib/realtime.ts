import { env } from '@config/env';

/**
 * Helpers for building realtime endpoint URLs (Server-Sent Events for location
 * tracking, WebSocket for chat). Realtime endpoints live under
 * {@link env.realtimeBaseUrl}, which may be a same-origin path (e.g. "/api",
 * proxied in dev) or an absolute origin in production.
 */

/** Resolve a realtime path against the configured base into an absolute URL. */
function resolveRealtimeUrl(path: string): URL {
  const base = env.realtimeBaseUrl;
  const normalizedPath = path.startsWith('/') ? path : `/${path}`;

  // Absolute base (http/https/ws/wss) — resolve directly.
  if (/^[a-z]+:\/\//i.test(base)) {
    return new URL(`${base.replace(/\/$/, '')}${normalizedPath}`);
  }

  // Same-origin path base — resolve against the current window origin.
  const origin = typeof window !== 'undefined' ? window.location.origin : 'http://localhost';
  return new URL(`${base.replace(/\/$/, '')}${normalizedPath}`, origin);
}

/**
 * Build an absolute URL for a Server-Sent Events endpoint. The access token is
 * appended as a query parameter because the browser EventSource API cannot set
 * an Authorization header.
 */
export function buildSseUrl(path: string, accessToken?: string | null): string {
  const url = resolveRealtimeUrl(path);
  if (accessToken) {
    url.searchParams.set('access_token', accessToken);
  }
  return url.toString();
}

/**
 * Build an absolute ws:// or wss:// URL for a WebSocket endpoint, upgrading the
 * scheme from http/https as appropriate. The access token is appended as a
 * query parameter since the browser WebSocket API cannot set custom headers.
 */
export function buildWsUrl(path: string, accessToken?: string | null): string {
  const url = resolveRealtimeUrl(path);
  url.protocol =
    url.protocol === 'https:' ? 'wss:' : url.protocol === 'http:' ? 'ws:' : url.protocol;
  if (accessToken) {
    url.searchParams.set('access_token', accessToken);
  }
  return url.toString();
}
