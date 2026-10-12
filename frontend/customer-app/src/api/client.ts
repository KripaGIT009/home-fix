import axios, { type AxiosError, type AxiosInstance, type InternalAxiosRequestConfig } from 'axios';
import { env } from '@config/env';
import { CORRELATION_ID_HEADER, generateCorrelationId } from '@lib/correlation';
import { handleUnauthorized, readAccessToken, refreshAccessToken } from './tokenBridge';

/**
 * Normalized API error surfaced to the UI/query layer. Backend services return
 * a consistent error envelope; we defensively parse it here. Extends Error so
 * it can be thrown/rejected and inspected by error boundaries and TanStack
 * Query alike.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly correlationId?: string;
  /** Seconds to wait before retrying, from a 429's `Retry-After` header. */
  readonly retryAfterSeconds?: number;

  constructor(params: {
    status: number;
    code: string;
    message: string;
    correlationId?: string;
    retryAfterSeconds?: number;
  }) {
    super(params.message);
    this.name = 'ApiError';
    this.status = params.status;
    this.code = params.code;
    if (params.correlationId !== undefined) {
      this.correlationId = params.correlationId;
    }
    if (params.retryAfterSeconds !== undefined) {
      this.retryAfterSeconds = params.retryAfterSeconds;
    }
  }
}

interface BackendErrorBody {
  /** The backend's shared error envelope names the field errorCode. */
  errorCode?: string;
  code?: string;
  message?: string;
  error?: string;
}

/** Copy shown when the platform cannot be reached at all (offline, 502/503/504). */
export const UNREACHABLE_MESSAGE =
  "We can't reach HomeFix right now. Please try again in a moment.";
/** Copy shown for an unexpected server failure (500). */
export const SERVER_ERROR_MESSAGE = 'Something went wrong on our side. Please try again.';
/** Copy shown when nothing more specific is known. */
export const GENERIC_ERROR_MESSAGE = 'Something went wrong. Please try again.';

/** Statuses that mean "the platform is unreachable", not "your request was wrong". */
const UNREACHABLE_STATUSES = new Set([0, 502, 503, 504]);

const UUID_PATTERN =
  /\s*[:#]?\s*\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/gi;

const REASON_PHRASES = new Set([
  'bad request',
  'unauthorized',
  'forbidden',
  'not found',
  'method not allowed',
  'conflict',
  'unprocessable entity',
  'too many requests',
  'internal server error',
  'bad gateway',
  'service unavailable',
  'gateway timeout',
]);

/**
 * Removes internal identifiers from a server message so a customer never sees a
 * raw UUID, and rejects messages that are really transport noise (axios's
 * "Request failed with status code …", bare HTTP reason phrases).
 */
function sanitizeServerMessage(message: string | undefined): string | undefined {
  if (!message) return undefined;
  const cleaned = message
    .replace(UUID_PATTERN, '')
    .replace(/\s{2,}/g, ' ')
    .trim();
  if (!cleaned || /request failed with status code/i.test(cleaned)) return undefined;
  // A bare HTTP reason phrase ("Not Found", "Bad Request") explains nothing.
  if (REASON_PHRASES.has(cleaned.toLowerCase().replace(/\.$/, ''))) return undefined;
  return cleaned;
}

/** Fallback copy by status when the server sent nothing usable. */
function fallbackMessage(status: number): string {
  if (UNREACHABLE_STATUSES.has(status)) return UNREACHABLE_MESSAGE;
  if (status >= 500) return SERVER_ERROR_MESSAGE;
  switch (status) {
    case 401:
      return 'Your session has ended. Please sign in again.';
    case 403:
      return "You don't have access to this.";
    case 404:
      return "We couldn't find what you were looking for.";
    case 429:
      return 'Too many attempts. Please wait a moment and try again.';
    default:
      return GENERIC_ERROR_MESSAGE;
  }
}

/**
 * Human copy for a failed request. Outages and server faults always get calm,
 * generic copy (the server's own text there is internal detail); client errors
 * keep the server's message, which is written for the customer (validation,
 * coupon rejection, OTP lockout), once internal ids are stripped.
 */
function toUserMessage(status: number, body: BackendErrorBody | undefined): string {
  if (UNREACHABLE_STATUSES.has(status)) return UNREACHABLE_MESSAGE;
  if (status >= 500) return SERVER_ERROR_MESSAGE;
  return sanitizeServerMessage(body?.message) ?? fallbackMessage(status);
}

/**
 * The `Retry-After` header in seconds, when the server sent one as a number of
 * seconds (the HTTP-date form is not used by HomeFix services). A cross-origin
 * native build only sees it if the service exposes the header, so callers keep
 * a fallback for its absence.
 */
function readRetryAfter(error: AxiosError): number | undefined {
  const raw: unknown = error.response?.headers?.['retry-after'];
  const seconds = typeof raw === 'string' || typeof raw === 'number' ? Number(raw) : NaN;
  return Number.isFinite(seconds) && seconds >= 0 ? seconds : undefined;
}

function toApiError(error: AxiosError<BackendErrorBody>): ApiError {
  const status = error.response?.status ?? 0;
  // A proxy in front of a stopped service answers with an HTML page, not JSON.
  const rawBody = error.response?.data;
  const body = rawBody && typeof rawBody === 'object' ? rawBody : undefined;
  const correlationId = error.config?.headers?.[CORRELATION_ID_HEADER] as string | undefined;
  const retryAfterSeconds = readRetryAfter(error);

  return new ApiError({
    status,
    code:
      body?.errorCode ??
      body?.code ??
      (status === 0
        ? 'NETWORK_ERROR'
        : UNREACHABLE_STATUSES.has(status)
          ? 'SERVICE_UNAVAILABLE'
          : 'UNKNOWN_ERROR'),
    message: toUserMessage(status, body),
    ...(correlationId ? { correlationId } : {}),
    ...(retryAfterSeconds !== undefined ? { retryAfterSeconds } : {}),
  });
}

/** True when the error means the platform could not be reached (offline or 502/503/504). */
export function isUnreachableError(error: unknown): boolean {
  return isApiError(error) && UNREACHABLE_STATUSES.has(error.status);
}

/**
 * The message to show a customer for any thrown value. ApiErrors already carry
 * normalised copy; anything else (a render bug, a library error) gets generic
 * copy rather than its developer-facing text.
 */
export function friendlyErrorMessage(error: unknown, fallback = GENERIC_ERROR_MESSAGE): string {
  if (isApiError(error)) return error.message || fallback;
  return fallback;
}

/**
 * A request config carrying the "already retried" marker, so a single request
 * can be replayed at most once after a silent refresh.
 */
type RetriableRequestConfig = InternalAxiosRequestConfig & { retriedAfterRefresh?: boolean };

const sharedOptions = {
  timeout: 30_000,
  headers: {
    'Content-Type': 'application/json',
    Accept: 'application/json',
  },
};

/** The shared Axios instance used by all API calls in the app. */
export const apiClient: AxiosInstance = axios.create({
  ...sharedOptions,
  baseURL: env.apiBaseUrl,
});

/**
 * Instance for every public Auth Service call: the OTP pair, email sign-up,
 * password sign-in and reset, social login, token refresh and logout.
 *
 * It carries its own base URL because the Auth Service does not sit behind the
 * API Gateway — the web builds reach it through the `/api/auth` proxy rule, and
 * a native build, which has no proxy at all, points VITE_AUTH_BASE_URL straight
 * at it. It also sends no Authorization header (these endpoints authenticate by
 * body) and has no 401 interceptor: one there would recurse into the very
 * refresh it is serving.
 */
export const authClient: AxiosInstance = axios.create({
  ...sharedOptions,
  baseURL: env.authBaseUrl,
});

/**
 * Instance for the signed-in Auth Service endpoints (`/auth/me/**`): the
 * account's own email and password.
 *
 * Same base URL as `authClient`, but these calls authenticate by the access
 * token, so it carries the Authorization header and the 401 refresh-and-replay
 * exactly as `apiClient` does. It is kept apart from `authClient` because a
 * stale token sent to the public endpoints (OTP, refresh) would be rejected
 * there before the request body is ever looked at.
 */
export const accountClient: AxiosInstance = axios.create({
  ...sharedOptions,
  baseURL: env.authBaseUrl,
});

/** Tag an outbound request with a correlation id unless it already carries one. */
function withCorrelationId(config: InternalAxiosRequestConfig): InternalAxiosRequestConfig {
  if (!config.headers.has(CORRELATION_ID_HEADER)) {
    config.headers.set(CORRELATION_ID_HEADER, generateCorrelationId());
  }
  return config;
}

/** Inject the JWT Bearer token and a per-request correlation ID. */
function withBearerToken(config: InternalAxiosRequestConfig): InternalAxiosRequestConfig {
  const token = readAccessToken();
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`);
  }
  return withCorrelationId(config);
}

/**
 * Response error handler for a token-carrying client: normalize errors, and on
 * a 401 attempt one silent refresh + replay before giving up on the session.
 */
function refreshAndReplayOn401(client: AxiosInstance) {
  return async (error: AxiosError<BackendErrorBody>) => {
    const apiError = toApiError(error);
    if (apiError.status !== 401) {
      throw apiError;
    }

    const config = error.config as RetriableRequestConfig | undefined;
    // Retry once per request only: a 401 on the replayed request (or no usable
    // refresh token at all) means the session really is over, so bailing out
    // here is what stops an infinite refresh/retry loop.
    if (config && !config.retriedAfterRefresh) {
      config.retriedAfterRefresh = true;
      const accessToken = await refreshAccessToken();
      if (accessToken) {
        config.headers.set('Authorization', `Bearer ${accessToken}`);
        return client.request(config);
      }
    }

    handleUnauthorized();
    throw apiError;
  };
}

apiClient.interceptors.request.use(withBearerToken);
accountClient.interceptors.request.use(withBearerToken);
authClient.interceptors.request.use(withCorrelationId);

apiClient.interceptors.response.use((response) => response, refreshAndReplayOn401(apiClient));
accountClient.interceptors.response.use(
  (response) => response,
  refreshAndReplayOn401(accountClient),
);

authClient.interceptors.response.use(
  (response) => response,
  (error: AxiosError<BackendErrorBody>) => Promise.reject(toApiError(error)),
);

/** Type guard for the normalized ApiError type. */
export function isApiError(value: unknown): value is ApiError {
  return value instanceof ApiError;
}
