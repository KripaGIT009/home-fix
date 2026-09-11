import { apiClient } from '@api/client';
import type { BookingStatus } from '@lib/bookingStatus';

/**
 * Booking Service job-execution bindings for the Provider App (Requirements 9,
 * 11). These cover the Provider-facing slice of the Booking state machine:
 * viewing an assigned job, accepting/declining an offer, attaching before/after
 * photos, pausing/resuming with a mandatory reason, recording parts, and
 * marking the job complete.
 *
 * Endpoints (see design.md — Booking Service state machine, Requirement 9/11;
 * routed via the API Gateway). The `me` provider is resolved from the bearer
 * token, so only the booking id is passed from the client:
 * - GET   /bookings/{bookingId}                 — job detail (Req 11.1)
 * - POST  /bookings/{bookingId}/accept          — accept a job offer (Req 9)
 * - POST  /bookings/{bookingId}/decline         — decline a job offer (Req 9)
 * - POST  /bookings/{bookingId}/photos          — upload before/after photo(s)
 * - POST  /bookings/{bookingId}/start           — transition to JOB_STARTED (Req 9.5, 11.2)
 * - POST  /bookings/{bookingId}/pause           — transition to JOB_PAUSED (Req 11.5)
 * - POST  /bookings/{bookingId}/resume          — transition back to JOB_STARTED (Req 11.5)
 * - POST  /bookings/{bookingId}/parts           — add a parts/materials line item (Req 11.3)
 * - POST  /bookings/{bookingId}/complete        — transition to JOB_COMPLETED (Req 9.10, 11.4, 11.6)
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
  url: string;
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
  /** Customer display name (never full legal name — Requirement 11.1). */
  customerDisplayName: string;
  /** Full service address for on-site work. */
  serviceAddress: string;
  /** Free-text service description supplied by the Customer. */
  description: string;
  /** Customer's location coordinates for the navigation deep-link. */
  coordinates: { latitude: number; longitude: number };
  /** Customer-uploaded media references (Requirement 11.1). */
  customerMedia: JobMediaReference[];
  /** Photos the Provider has attached so far. */
  photos: JobPhoto[];
  /** Parts/materials recorded so far (Requirement 11.3). */
  parts: PartLineItem[];
  currency: string;
  /** Estimated payout for the job, when known. */
  estimatedEarning: number | null;
}

/** Offer detail shown on the Job Request screen (Requirement 28.8). */
export interface JobOffer {
  bookingId: string;
  reference: string;
  serviceName: string;
  isEmergency: boolean;
  scheduledAt: string;
  /** Short customer area/locality label (no precise address before accept). */
  customerArea: string;
  description: string;
  estimatedEarning: number | null;
  currency: string;
  /**
   * Seconds remaining before the offer auto-expires (Requirement 28.8 — the
   * 60-second accept/decline window). Derived by the server from when the
   * offer was made; the client counts down locally from this value.
   */
  expiresInSeconds: number;
}

/** GET /bookings/{bookingId}/offer — offer detail for the Job Request screen. */
export async function fetchJobOffer(bookingId: string): Promise<JobOffer> {
  const { data } = await apiClient.get<JobOffer>(`/bookings/${bookingId}/offer`);
  return data;
}

/** GET /bookings/{bookingId} — full job detail (Requirement 11.1). */
export async function fetchJobDetail(bookingId: string): Promise<JobDetail> {
  const { data } = await apiClient.get<JobDetail>(`/bookings/${bookingId}`);
  return data;
}

/** POST /bookings/{bookingId}/accept — accept a job offer (Requirement 9). */
export async function acceptJob(bookingId: string): Promise<JobDetail> {
  const { data } = await apiClient.post<JobDetail>(`/bookings/${bookingId}/accept`);
  return data;
}

/** POST /bookings/{bookingId}/decline — decline a job offer (Requirement 9). */
export async function declineJob(bookingId: string): Promise<void> {
  await apiClient.post(`/bookings/${bookingId}/decline`);
}

/**
 * POST /bookings/{bookingId}/photos — upload one or more before/after photos.
 *
 * Sent as multipart/form-data: a `kind` field plus one `photo` part per file,
 * mirroring the Customer App booking-media convention.
 */
export async function uploadJobPhotos(
  bookingId: string,
  kind: PhotoKind,
  files: File[],
): Promise<JobPhoto[]> {
  const form = new FormData();
  form.append('kind', kind);
  for (const file of files) {
    form.append('photo', file, file.name);
  }
  const { data } = await apiClient.post<JobPhoto[]>(`/bookings/${bookingId}/photos`, form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
  return data;
}

/**
 * POST /bookings/{bookingId}/start — transition to JOB_STARTED.
 *
 * The Booking Service rejects this with a 4xx error if no before-photo is
 * attached (Requirement 11.2); the client guards the button up front too.
 */
export async function startJob(bookingId: string): Promise<JobDetail> {
  const { data } = await apiClient.post<JobDetail>(`/bookings/${bookingId}/start`);
  return data;
}

/**
 * POST /bookings/{bookingId}/pause — transition to JOB_PAUSED with a mandatory
 * reason of 1–500 characters (Requirement 11.5).
 */
export async function pauseJob(bookingId: string, reason: string): Promise<JobDetail> {
  const { data } = await apiClient.post<JobDetail>(`/bookings/${bookingId}/pause`, { reason });
  return data;
}

/** POST /bookings/{bookingId}/resume — transition back to JOB_STARTED (Req 11.5). */
export async function resumeJob(bookingId: string): Promise<JobDetail> {
  const { data } = await apiClient.post<JobDetail>(`/bookings/${bookingId}/resume`);
  return data;
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
 * trigger price recalculation (Requirement 11.3).
 */
export async function addJobPart(
  bookingId: string,
  payload: AddPartPayload,
): Promise<PartLineItem> {
  const { data } = await apiClient.post<PartLineItem>(`/bookings/${bookingId}/parts`, payload);
  return data;
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

/**
 * GET /bookings/{bookingId}/summary — completion summary for a job.
 *
 * Returns net duration, parts, and final price. Available once the job has
 * enough context to summarise it; used by the Job Completion screen when the
 * Provider revisits it after completing (Requirement 11.6).
 */
export async function fetchJobCompletionSummary(bookingId: string): Promise<JobCompletionSummary> {
  const { data } = await apiClient.get<JobCompletionSummary>(`/bookings/${bookingId}/summary`);
  return data;
}

/**
 * POST /bookings/{bookingId}/complete — transition to JOB_COMPLETED.
 *
 * The Booking Service rejects this with a 4xx error if no after-photo is
 * attached (Requirement 11.4); the client guards the button up front too.
 */
export async function completeJob(bookingId: string): Promise<JobCompletionSummary> {
  const { data } = await apiClient.post<JobCompletionSummary>(`/bookings/${bookingId}/complete`);
  return data;
}
