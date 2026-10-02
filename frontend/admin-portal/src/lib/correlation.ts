/**
 * Generates an RFC 4122 v4 UUID for use as the X-Correlation-ID header.
 * Falls back to a Math.random-based generator when crypto.randomUUID is
 * unavailable (older browsers / insecure contexts).
 */
export function generateCorrelationId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }

  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (char) => {
    const rand = (Math.random() * 16) | 0;
    const value = char === 'x' ? rand : (rand & 0x3) | 0x8;
    return value.toString(16);
  });
}

/**
 * Generates a fresh Idempotency-Key value. Same v4 UUID source, and so the same
 * fallback, as the correlation id: crypto.randomUUID is missing outside secure
 * contexts (e.g. the portal served over plain HTTP on a LAN address).
 */
export const generateIdempotencyKey = generateCorrelationId;

/** Header name used to correlate a client request with backend traces. */
export const CORRELATION_ID_HEADER = 'X-Correlation-ID';
