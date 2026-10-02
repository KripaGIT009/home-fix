import { apiClient } from '@api/client';

/**
 * Provider earnings & settlement bindings (Requirement 14).
 *
 * Endpoints (see design.md — Provider Service PROVIDER_EARNINGS + SETTLEMENT
 * data models; Payment Service settlement transfer). The `me` provider is
 * resolved from the bearer token:
 * - GET  /providers/me/earnings?page&size     — paginated per-job earnings (Req 14.5)
 * - GET  /providers/me/settlement-info        — wallet balance + verified bank accounts (Req 14.2)
 * - POST /providers/me/settlements            — request a settlement (Req 14.2, 14.3)
 * - GET  /providers/me/settlements            — settlement request history (Req 14.3)
 *
 * All calls flow through the shared Axios client (JWT + correlation id +
 * normalized ApiError).
 */

/** One job's earnings row in the paginated history (Requirement 14.5). */
export interface EarningsHistoryItem {
  bookingId: string;
  /** Booking reference number for the job. */
  reference: string;
  /** Subcategory display name. */
  serviceName: string;
  /** ISO-8601 date the earning was credited. */
  creditedAt: string;
  /** Gross earnings for the job. */
  gross: number;
  /** Platform fee deducted (Requirement 14.1, 14.5). */
  platformFee: number;
  /** Net earning credited to the wallet (gross - fee). */
  net: number;
  currency: string;
}

/** One page of earnings history (Requirement 14.5 — paginated). */
export interface EarningsHistoryPage {
  items: EarningsHistoryItem[];
  page: number;
  pageSize: number;
  totalItems: number;
  totalPages: number;
}

/** One earning row as the Provider Service sends it. */
interface EarningResponse {
  id: string;
  bookingId: string | null;
  bookingReference: string | null;
  /** Ledger entry kind, e.g. `JOB_CREDIT`. */
  type: string;
  gross: number;
  platformFee: number;
  net: number;
  creditedAt: string;
}

/** The Spring Data page envelope the Provider Service answers with (0-based `number`). */
interface SpringPage<T> {
  content: T[];
  number: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** `JOB_CREDIT` → "Job credit": the ledger kind is the only label the row carries. */
function describeEarningType(type: string): string {
  const words = type.toLowerCase().split('_').filter(Boolean).join(' ');
  return words ? words.charAt(0).toUpperCase() + words.slice(1) : 'Earning';
}

/**
 * GET /providers/me/earnings?page&size — paginated per-job earnings history,
 * newest first (Requirement 14.5).
 *
 * `page` is 1-based here, like the screen's pager; the Provider Service pages
 * from 0 and answers with a Spring Data page, which is adapted to
 * {@link EarningsHistoryPage} in this one place.
 */
export async function fetchEarningsHistory(
  page: number,
  pageSize: number,
): Promise<EarningsHistoryPage> {
  const { data } = await apiClient.get<SpringPage<EarningResponse>>('/providers/me/earnings', {
    params: { page: page - 1, size: pageSize },
  });
  return {
    items: data.content.map((e) => ({
      bookingId: e.bookingId ?? '',
      reference: e.bookingReference ?? '',
      serviceName: describeEarningType(e.type),
      creditedAt: e.creditedAt,
      gross: e.gross,
      platformFee: e.platformFee,
      net: e.net,
      currency: 'INR',
    })),
    page: data.number + 1,
    pageSize: data.size,
    totalItems: data.totalElements,
    totalPages: Math.max(data.totalPages, 1),
  };
}

/** A verified bank account a settlement can be paid into (Requirement 14.2). */
export interface BankAccount {
  id: string;
  /** Masked account label, e.g. "HDFC ••••1234". */
  label: string;
  verified: boolean;
}

/** Wallet + bank-account context needed to request a settlement (Req 14.2). */
export interface SettlementInfo {
  /** Available wallet balance a settlement can draw from. */
  availableBalance: number;
  currency: string;
  /** Bank accounts on file; only verified ones can receive settlements. */
  bankAccounts: BankAccount[];
}

/** GET /providers/me/settlement-info — balance + bank accounts (Requirement 14.2). */
export async function fetchSettlementInfo(): Promise<SettlementInfo> {
  const { data } = await apiClient.get<
    Omit<SettlementInfo, 'bankAccounts'> & {
      bankAccounts: Array<{ id: string; masked?: string; label?: string; verified: boolean }>;
    }
  >('/providers/me/settlement-info');
  return {
    ...data,
    // The Provider Service calls the masked account text `masked`.
    bankAccounts: (data.bankAccounts ?? []).map((a) => ({
      id: a.id,
      label: a.label ?? a.masked ?? 'Bank account',
      verified: a.verified,
    })),
  };
}

/** Settlement request lifecycle states (Requirement 14.3). */
export type SettlementStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';

/** A settlement request in the Provider's history (Requirement 14.3). */
export interface SettlementRequest {
  id: string;
  amount: number;
  currency: string;
  status: SettlementStatus;
  /** ISO-8601 timestamp the request was made. */
  requestedAt: string;
  /** ISO-8601 timestamp the settlement completed, when applicable. */
  completedAt: string | null;
}

/** GET /providers/me/settlements — settlement request history (Requirement 14.3). */
export async function fetchSettlements(): Promise<SettlementRequest[]> {
  const { data } = await apiClient.get<SettlementRequest[]>('/providers/me/settlements');
  return data;
}

/** Payload to request a settlement (Requirement 14.2). */
export interface SettlementRequestPayload {
  /** Amount to settle; validated 1.00–available balance server-side (Req 14.2a). */
  amount: number;
  /** Verified bank account to receive the transfer (Req 14.2b). */
  bankAccountId: string;
}

/**
 * POST /providers/me/settlements — request a settlement.
 *
 * The Provider Service validates the amount range and that a verified bank
 * account exists, rejecting with a descriptive 4xx error otherwise
 * (Requirement 14.2).
 */
export async function requestSettlement(
  payload: SettlementRequestPayload,
): Promise<SettlementRequest> {
  const { data } = await apiClient.post<SettlementRequest>('/providers/me/settlements', payload);
  return data;
}
