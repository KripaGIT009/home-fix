import { apiClient } from '@api/client';
import type { BookingStatus } from '@lib/bookingStatus';

/**
 * Job bindings for the Provider App (Requirements 8.5-8.7, 9, 11): job offers
 * from the Dispatch Engine, and the Provider-facing slice of the Booking state
 * machine — viewing an assigned job, attaching before/after photos,
 * pausing/resuming with a mandatory reason, recording parts, and marking the
 * job complete.
 *
 * Endpoints (routed via the API Gateway). The provider is resolved from the
 * bearer token, so only the booking id is passed from the client; the Booking
 * Service accepts the booking's UUID or its reference on every path and answers
 * 404 to anyone but the assigned provider:
 * - GET   /dispatch/offers                      — my open offers, soonest-expiring first
 * - GET   /dispatch/offers/{bookingId}          — one offer made to me, in any status
 * - POST  /dispatch/offers/{bookingId}/accept   — accept it (Req 8.6)
 * - POST  /dispatch/offers/{bookingId}/decline  — decline it (Req 8.7)
 * - POST  /bookings/{bookingId}/assignment/acceptance — accept a job my agency assigned (Req MT-6.1)
 * - POST  /bookings/{bookingId}/assignment/rejection  — decline it (Req MT-6.2)
 * - GET   /bookings/{bookingId}                 — job detail (Req 11.1)
 * - POST  /bookings/{bookingId}/on-the-way      — transition to PROVIDER_ON_THE_WAY (Req 9.3)
 * - POST  /bookings/{bookingId}/arrived         — transition to PROVIDER_ARRIVED (Req 9.4)
 * - POST  /bookings/{bookingId}/photos          — upload one before/after photo
 * - POST  /bookings/{bookingId}/start           — transition to JOB_STARTED (Req 9.5, 11.2)
 * - POST  /bookings/{bookingId}/pause           — transition to JOB_PAUSED (Req 11.5)
 * - POST  /bookings/{bookingId}/resume          — transition back to JOB_STARTED (Req 11.5)
 * - POST  /bookings/{bookingId}/parts           — add a parts/materials line item (Req 11.3)
 * - POST  /bookings/{bookingId}/complete        — transition to JOB_COMPLETED (Req 9.10, 11.4, 11.6)
 *
 * Every transition answers with the bare booking, not the job detail, so the
 * mutations refetch the detail rather than trusting the response body.
 *
 * All calls flow through the shared Axios client (JWT + correlation id +
 * normalized ApiError).
 */

/** Which side of the job a photo documents (Requirement 11.2, 11.4). */
export type PhotoKind = 'BEFORE' | 'AFTER';

/** A media reference attached to the booking. */
export interface JobMediaReference {
  id: string;
  /** Displayable URL (signed) for the media. */
  url: string;
  /** Optional caption/label supplied by the uploader. */
  label?: string;
}

/** A photo the Provider has attached to the job. */
export interface JobPhoto {
  id: string;
  kind: PhotoKind;
  /** Displayable URL; absent while the Booking Service does not serve stored media back. */
  url?: string;
  uploadedAt: string;
}

/** A parts/materials line item recorded during execution (Requirement 11.3). */
export interface PartLineItem {
  id: string;
  itemName: string;
  quantity: number;
  unitCost: number;
}

/**
 * Full job detail for a Provider (Requirement 11.1): customer display name,
 * service address, description, customer-uploaded media, and coordinates for a
 * navigation deep-link.
 */
export interface JobDetail {
  bookingId: string;
  reference: string;
  status: BookingStatus;
  serviceName: string;
  isEmergency: boolean;
  scheduledAt: string;
  /** Customer display name (never full legal name — Requirement 11.1); `null` when not shared. */
  customerDisplayName: string | null;
  /** Full service address for on-site work; `null` until the Booking Service can resolve it. */
  serviceAddress: string | null;
  /** Free-text service description supplied by the Customer. */
  description: string;
  /** Customer's location coordinates for the navigation deep-link; `null` when unknown. */
  coordinates: { latitude: number; longitude: number } | null;
  /** Customer-uploaded media references (Requirement 11.1). */
  customerMedia: JobMediaReference[];
  /** Photos the Provider has attached so far. */
  photos: JobPhoto[];
  /** Parts/materials recorded so far (Requirement 11.3). */
  parts: PartLineItem[];
  currency: string;
  /** Estimated payout for the job, when known. */
  estimatedEarning: number | null;
  /** What the job costs: the final total once parts are priced in or it is done, else the estimate. */
  amount: number | null;
  /** Net working time (pauses excluded), set once the job is completed (Req 11.6). */
  netDurationSeconds: number | null;
  /**
   * The agency (Tenant) that assigned the job, sent while it is
   * `PROVIDER_ASSIGNED` and waiting for this provider's answer (Req MT-6.4);
   * `null` otherwise.
   */
  tenantName: string | null;
}

/**
 * Where a job offer is in its lifecycle. Only `PENDING` can still be answered;
 * `EXPIRED` means the response window closed (a late accept is refused) and
 * `WITHDRAWN` that the booking was cancelled while the offer was open.
 */
export type JobOfferStatus = 'PENDING' | 'ACCEPTED' | 'DECLINED' | 'EXPIRED' | 'WITHDRAWN';

/**
 * A job offer as the Dispatch Engine reports it (Requirement 28.8).
 *
 * Dispatch knows the booking only as far as matching needs it, so the offer
 * carries no service name, address, description or earning: the full job
 * detail comes from the Booking Service once the offer is accepted. Optional
 * facts are `null` when the booking event did not supply them.
 */
export interface JobOffer {
  bookingId: string;
  status: JobOfferStatus;
  /** Customer-facing booking reference, e.g. "HFX-2026-0004821". */
  reference: string | null;
  emergency: boolean;
  subcategoryId: string | null;
  /** Booked slot (ISO 8601); `null` for an as-soon-as-possible booking. */
  scheduledAt: string | null;
  offeredAt: string;
  expiresAt: string;
  /**
   * Whole seconds left to respond, by the server's clock when it answered; 0
   * once the window has closed or the offer was decided. The client counts down
   * locally from this rather than from `expiresAt`, so device clock skew does
   * not shorten or stretch the window.
   */
  expiresInSeconds: number;
  /** Full length of the response window the offer was made with. */
  timeoutSeconds: number;
}

/** Error codes the offer endpoints answer with (404 / 409). */
export const OFFER_ERROR = {
  notFound: 'OFFER_NOT_FOUND',
  expired: 'OFFER_EXPIRED',
  alreadyDecided: 'OFFER_ALREADY_DECIDED',
} as const;

/** GET /dispatch/offers — my open offers, soonest-expiring first. */
export async function fetchJobOffers(): Promise<JobOffer[]> {
  const { data } = await apiClient.get<JobOffer[]>('/dispatch/offers');
  return data;
}

/** GET /dispatch/offers/{bookingId} — one offer made to me (404 for anyone else). */
export async function fetchJobOffer(bookingId: string): Promise<JobOffer> {
  const { data } = await apiClient.get<JobOffer>(`/dispatch/offers/${bookingId}`);
  return data;
}

/**
 * Normalises a Booking Service booking into a {@link JobDetail}.
 *
 * The Booking Service serves one booking read model to customers, providers and
 * staff (`referenceNumber`, `emergency`, …) and omits whatever it cannot supply,
 * so every field the screens read is defaulted here, in one place. A screen must
 * never crash because an optional fact is absent: it hides that part instead.
 */
export function toJobDetail(raw: unknown): JobDetail {
  const r = (raw ?? {}) as Record<string, unknown>;
  const str = (v: unknown): string | null => (typeof v === 'string' && v.trim() !== '' ? v : null);
  const arr = <T>(v: unknown): T[] => (Array.isArray(v) ? (v as T[]) : []);
  const coords = r.coordinates as { latitude?: unknown; longitude?: unknown } | null | undefined;
  return {
    bookingId: str(r.bookingId) ?? '',
    reference: str(r.reference) ?? str(r.referenceNumber) ?? '',
    status: r.status as BookingStatus,
    serviceName: str(r.serviceName) ?? 'Service',
    isEmergency: Boolean(r.isEmergency ?? r.emergency ?? false),
    scheduledAt: str(r.scheduledAt) ?? str(r.date) ?? str(r.createdAt) ?? '',
    customerDisplayName: str(r.customerDisplayName),
    serviceAddress: str(r.serviceAddress) ?? str(r.address),
    description: str(r.description) ?? '',
    coordinates:
      coords && typeof coords.latitude === 'number' && typeof coords.longitude === 'number'
        ? { latitude: coords.latitude, longitude: coords.longitude }
        : null,
    customerMedia: arr<JobMediaReference>(r.customerMedia),
    photos: arr<JobPhoto>(r.photos),
    parts: arr<PartLineItem>(r.parts),
    currency: str(r.currency) ?? 'INR',
    estimatedEarning: typeof r.estimatedEarning === 'number' ? r.estimatedEarning : null,
    amount: typeof r.amount === 'number' ? r.amount : null,
    netDurationSeconds: typeof r.netDurationSeconds === 'number' ? r.netDurationSeconds : null,
    tenantName: str(r.tenantName),
  };
}

/** GET /bookings/{bookingId} — full job detail (Requirement 11.1). */
export async function fetchJobDetail(bookingId: string): Promise<JobDetail> {
  const { data } = await apiClient.get<unknown>(`/bookings/${bookingId}`);
  return toJobDetail(data);
}

/**
 * POST /dispatch/offers/{bookingId}/accept — accept a job offer (Req 8.6).
 *
 * Answers with the decided offer. 409 `OFFER_EXPIRED` once the window has
 * closed, 409 `OFFER_ALREADY_DECIDED` for a second answer.
 */
export async function acceptJobOffer(bookingId: string): Promise<JobOffer> {
  const { data } = await apiClient.post<JobOffer>(`/dispatch/offers/${bookingId}/accept`);
  return data;
}

/** POST /dispatch/offers/{bookingId}/decline — decline a job offer (Req 8.7). */
export async function declineJobOffer(bookingId: string): Promise<JobOffer> {
  const { data } = await apiClient.post<JobOffer>(`/dispatch/offers/${bookingId}/decline`);
  return data;
}

/**
 * POST /bookings/{bookingId}/assignment/acceptance — accept a job the
 * provider's agency assigned (Requirement MT-6.1). The booking moves to
 * PROVIDER_ACCEPTED and from there runs exactly like an automatically matched
 * job. 404 when the job is not (or no longer) assigned to this provider, 409
 * when it has moved on (e.g. the customer cancelled).
 */
export async function acceptAssignment(bookingId: string): Promise<void> {
  await apiClient.post(`/bookings/${bookingId}/assignment/acceptance`);
}

/**
 * POST /bookings/{bookingId}/assignment/rejection — decline an assigned job
 * (Requirement MT-6.2). The booking returns to the agency's queue for someone
 * else and stops being this provider's, so its detail answers 404 afterwards.
 */
export async function declineAssignment(bookingId: string): Promise<void> {
  await apiClient.post(`/bookings/${bookingId}/assignment/rejection`);
}

/** POST /bookings/{bookingId}/on-the-way — the provider has set off (Req 9.3). */
export async function markOnTheWay(bookingId: string): Promise<void> {
  await apiClient.post(`/bookings/${bookingId}/on-the-way`);
}

/** POST /bookings/{bookingId}/arrived — the provider is at the address (Req 9.4). */
export async function markArrived(bookingId: string): Promise<void> {
  await apiClient.post(`/bookings/${bookingId}/arrived`);
}

/**
 * POST /locations/{bookingId} — the provider's current position while on the
 * way (Requirement 10.1). The Location Service records the caller as the
 * provider and accepts at most one update per 5 s.
 */
export async function postLocation(
  bookingId: string,
  latitude: number,
  longitude: number,
): Promise<void> {
  await apiClient.post(`/locations/${bookingId}`, { latitude, longitude });
}

/** The Booking Service's name for each photo kind. */
const PHOTO_TYPE: Record<PhotoKind, string> = {
  BEFORE: 'BEFORE_PHOTO',
  AFTER: 'AFTER_PHOTO',
};

/**
 * POST /bookings/{bookingId}/photos — attach before/after photos.
 *
 * The endpoint takes one photo per request, as multipart/form-data with a
 * `type` field (`BEFORE_PHOTO` / `AFTER_PHOTO`) and a `file` part, and answers
 * 204; several files are sent one after another.
 */
export async function uploadJobPhotos(
  bookingId: string,
  kind: PhotoKind,
  files: File[],
): Promise<void> {
  for (const file of files) {
    const form = new FormData();
    form.append('type', PHOTO_TYPE[kind]);
    form.append('file', file, file.name);
    await apiClient.post(`/bookings/${bookingId}/photos`, form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  }
}

/**
 * POST /bookings/{bookingId}/start — transition to JOB_STARTED.
 *
 * The Booking Service rejects this with a 4xx error if no before-photo is
 * attached (Requirement 11.2); the client guards the button up front too.
 */
export async function startJob(bookingId: string): Promise<void> {
  await apiClient.post(`/bookings/${bookingId}/start`);
}

/**
 * POST /bookings/{bookingId}/pause — transition to JOB_PAUSED with a mandatory
 * reason of 1–500 characters (Requirement 11.5).
 */
export async function pauseJob(bookingId: string, reason: string): Promise<void> {
  await apiClient.post(`/bookings/${bookingId}/pause`, { reason });
}

/** POST /bookings/{bookingId}/resume — transition back to JOB_STARTED (Req 11.5). */
export async function resumeJob(bookingId: string): Promise<void> {
  await apiClient.post(`/bookings/${bookingId}/resume`);
}

/** Payload for a new parts/materials line item (Requirement 11.3). */
export interface AddPartPayload {
  itemName: string;
  /** Minimum 1 (Requirement 11.3). */
  quantity: number;
  /** Minimum 0.01 (Requirement 11.3). */
  unitCost: number;
}

/**
 * POST /bookings/{bookingId}/parts — record a parts/materials line item and
 * trigger price recalculation (Requirement 11.3). The job then waits in
 * CUSTOMER_APPROVAL_PENDING until the customer approves or rejects the new
 * quote (Requirement 9.7-9.9).
 */
export async function addJobPart(bookingId: string, payload: AddPartPayload): Promise<void> {
  await apiClient.post(`/bookings/${bookingId}/parts`, payload);
}

/** Summary returned when a job is completed (Requirement 11.6, 9.10). */
export interface JobCompletionSummary {
  bookingId: string;
  reference: string;
  status: BookingStatus;
  /** Net job duration in minutes (JOB_STARTED intervals minus pauses — Req 11.6). */
  netDurationMinutes: number;
  parts: PartLineItem[];
  /** Sum of parts (quantity * unitCost). */
  partsTotal: number;
  /** Final price for the job after recalculation. */
  finalPrice: number;
  currency: string;
}

/** Builds the completion summary from a completed job's detail (Requirement 11.6). */
export function toCompletionSummary(job: JobDetail): JobCompletionSummary {
  const partsTotal = job.parts.reduce((sum, part) => sum + part.quantity * part.unitCost, 0);
  return {
    bookingId: job.bookingId,
    reference: job.reference,
    status: job.status,
    netDurationMinutes: Math.round((job.netDurationSeconds ?? 0) / 60),
    parts: job.parts,
    partsTotal,
    finalPrice: job.amount ?? 0,
    currency: job.currency,
  };
}

/**
 * Completion summary for a job: net duration, parts and final price, read from
 * the job detail. Used by the Job Completion screen, including when the
 * Provider revisits it later (Requirement 11.6).
 */
export async function fetchJobCompletionSummary(bookingId: string): Promise<JobCompletionSummary> {
  return toCompletionSummary(await fetchJobDetail(bookingId));
}

/**
 * POST /bookings/{bookingId}/complete — transition to JOB_COMPLETED, then read
 * back the summary.
 *
 * The Booking Service rejects this with a 4xx error if no after-photo is
 * attached (Requirement 11.4); the client guards the button up front too.
 */
export async function completeJob(bookingId: string): Promise<JobCompletionSummary> {
  await apiClient.post(`/bookings/${bookingId}/complete`);
  return fetchJobCompletionSummary(bookingId);
}
