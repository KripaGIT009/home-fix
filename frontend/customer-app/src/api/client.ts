import axios, { type AxiosError, type AxiosInstance, type InternalAxiosRequestConfig } from 'axios';
import { env } from '@config/env';
import { CORRELATION_ID_HEADER, generateCorrelationId } from '@lib/correlation';
import { handleUnauthorized, readAccessToken } from './tokenBridge';

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

  constructor(params: { status: number; code: string; message: string; correlationId?: string }) {
    super(params.message);
    this.name = 'ApiError';
    this.status = params.status;
    this.code = params.code;
    if (params.correlationId !== undefined) {
      this.correlationId = params.correlationId;
    }
  }
}

interface BackendErrorBody {
  code?: string;
  message?: string;
  error?: string;
}

function toApiError(error: AxiosError<BackendErrorBody>): ApiError {
  const status = error.response?.status ?? 0;
  const body = error.response?.data;
  const correlationId = error.config?.headers?.[CORRELATION_ID_HEADER] as string | undefined;

  return new ApiError({
    status,
    code: body?.code ?? (status === 0 ? 'NETWORK_ERROR' : 'UNKNOWN_ERROR'),
    message:
      body?.message ??
      body?.error ??
      (status === 0 ? 'Unable to reach the server. Check your connection.' : error.message),
    ...(correlationId ? { correlationId } : {}),
  });
}

/** The shared Axios instance used by all API calls in the app. */
export const apiClient: AxiosInstance = axios.create({
  baseURL: env.apiBaseUrl,
  timeout: 30_000,
  headers: {
    'Content-Type': 'application/json',
    Accept: 'application/json',
  },
});

// Request interceptor: inject JWT Bearer token and a per-request correlation ID.
apiClient.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = readAccessToken();
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`);
  }
  if (!config.headers.has(CORRELATION_ID_HEADER)) {
    config.headers.set(CORRELATION_ID_HEADER, generateCorrelationId());
  }
  return config;
});

// Response interceptor: normalize errors and react to 401s.
apiClient.interceptors.response.use(
  (response) => response,
  (error: AxiosError<BackendErrorBody>) => {
    const apiError = toApiError(error);
    if (apiError.status === 401) {
      handleUnauthorized();
    }
    return Promise.reject(apiError);
  },
);

/** Type guard for the normalized ApiError type. */
export function isApiError(value: unknown): value is ApiError {
  return value instanceof ApiError;
}
