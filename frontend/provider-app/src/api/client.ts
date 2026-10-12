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
  /**
   * The `Retry-After` header of a 429 (seconds), e.g. how long a password
   * sign-in stays locked or when the next code may be requested.
   */
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
  /** The shared ErrorResponseDto envelope's code field. */
  errorCode?: string;
  code?: string;
  message?: string;
  error?: string;
}

/** The `Retry-After` header in seconds; the services only ever send the delay form. */
function parseRetryAfter(error: AxiosError): number | undefined {
  const raw: unknown = error.response?.headers?.['retry-after'];
  const seconds = typeof raw === 'string' || typeof raw === 'number' ? Number(raw) : NaN;
  return Number.isFinite(seconds) && seconds >= 0 ? seconds : undefined;
}

function toApiError(error: AxiosError<BackendErrorBody>): ApiError {
  const status = error.response?.status ?? 0;
  const body = error.response?.data;
  const correlationId = error.config?.headers?.[CORRELATION_ID_HEADER] as string | undefined;
  const retryAfterSeconds = parseRetryAfter(error);

  return new ApiError({
    status,
    code: body?.errorCode ?? body?.code ?? (status === 0 ? 'NETWORK_ERROR' : 'UNKNOWN_ERROR'),
    message:
      body?.message ??
      body?.error ??
      (status === 0 ? 'Unable to reach the server. Check your connection.' : error.message),
    ...(correlationId ? { correlationId } : {}),
    ...(retryAfterSeconds !== undefined ? { retryAfterSeconds } : {}),
  });
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
 * Instance for every Auth Service call: the OTP pair, social login, token
 * refresh and logout.
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
 * Instance for the signed-in Auth Service endpoints (`/auth/me/...`: the
 * account's own email and password).
 *
 * Same base URL as `authClient`, since these live on the Auth Service too, but
 * they authenticate by bearer token, so it carries the `apiClient` behaviour:
 * the Authorization header, and one silent refresh + replay on a 401. The
 * refresh itself goes through `authClient`, so it cannot recurse.
 */
export const authSessionClient: AxiosInstance = axios.create({
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

// Request interceptor: inject JWT Bearer token and a per-request correlation ID.
function withBearerToken(config: InternalAxiosRequestConfig): InternalAxiosRequestConfig {
  const token = readAccessToken();
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`);
  }
  return withCorrelationId(config);
}

apiClient.interceptors.request.use(withBearerToken);
authSessionClient.interceptors.request.use(withBearerToken);

authClient.interceptors.request.use(withCorrelationId);

// Response interceptor: normalize errors, and on a 401 attempt one silent
// refresh + replay before giving up on the session.
function refreshOnUnauthorized(instance: AxiosInstance) {
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
        return instance.request(config);
      }
    }

    handleUnauthorized();
    throw apiError;
  };
}

apiClient.interceptors.response.use((response) => response, refreshOnUnauthorized(apiClient));
authSessionClient.interceptors.response.use(
  (response) => response,
  refreshOnUnauthorized(authSessionClient),
);

authClient.interceptors.response.use(
  (response) => response,
  (error: AxiosError<BackendErrorBody>) => Promise.reject(toApiError(error)),
);

/** Type guard for the normalized ApiError type. */
export function isApiError(value: unknown): value is ApiError {
  return value instanceof ApiError;
}
