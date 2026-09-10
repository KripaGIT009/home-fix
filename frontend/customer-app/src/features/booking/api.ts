import { apiClient } from '@api/client';
import type { AddressFormValues } from './schemas';

/**
 * Pricing Engine + Booking Service API bindings (Requirements 6, 7, 8).
 *
 * Endpoints (see design.md — Pricing Engine and Booking Service, routed via
 * the API Gateway):
 * - POST /pricing/estimate  — itemized price estimate for a prospective booking
 * - POST /bookings          — create a booking (multipart: payload + media)
 *
 * Both flow through the shared Axios client, inheriting the Authorization and
 * X-Correlation-ID interceptors and the normalized ApiError rejection.
 */

/** A single line item in the itemized price breakdown (Requirement 6.9). */
export interface PriceLineItem {
  /** Stable component key, e.g. "basePrice", "emergencyCharge". */
  key: string;
  /** Human-readable label supplied by the Pricing Engine. */
  label: string;
  /** Signed amount — negative for discounts and coupons. */
  amount: number;
}

/**
 * Itemized price estimate returned by the Pricing Engine before confirmation
 * (Requirements 6.9, 7.3). `total` equals the sum of all line-item amounts.
 */
export interface PriceEstimate {
  /** Opaque estimate handle passed back to /bookings at confirmation. */
  estimateId: string;
  currency: string;
  lineItems: PriceLineItem[];
  total: number;
  /** Present when a coupon was applied successfully. */
  appliedCoupon?: {
    code: string;
    discountAmount: number;
  };
}

export interface EstimateRequestPayload {
  subcategoryId: string;
  isEmergency: boolean;
  /** Omitted for emergency bookings (dispatched immediately). */
  scheduledAt?: string;
  address: AddressFormValues;
  /** Optional coupon code to validate and apply (Requirement 6.10). */
  couponCode?: string;
}

/**
 * POST /pricing/estimate — request an itemized estimate.
 *
 * A 503 from the Pricing Engine surfaces as an ApiError with status 503; the
 * UI must not proceed to booking creation in that case (Requirement 7.4).
 * A rejected coupon surfaces as a 4xx ApiError whose message identifies the
 * violated constraint (Requirement 6.10).
 */
export async function requestEstimate(payload: EstimateRequestPayload): Promise<PriceEstimate> {
  const { data } = await apiClient.post<PriceEstimate>('/pricing/estimate', payload);
  return data;
}

export interface CreateBookingPayload {
  subcategoryId: string;
  isEmergency: boolean;
  scheduledAt?: string;
  address: AddressFormValues;
  description?: string;
  /** Echoes the confirmed estimate so the server can reconcile the price. */
  estimateId: string;
  couponCode?: string;
  /** Validated media files to upload with the booking (Requirement 7.2). */
  media: File[];
}

/** Response after creating a booking (Requirements 7.1, 8.1). */
export interface CreateBookingResponse {
  bookingId: string;
  referenceNumber: string;
  status: string;
  isEmergency: boolean;
}

/**
 * POST /bookings — create a booking with optional media.
 *
 * Sent as multipart/form-data: a JSON `payload` part plus one `media` part per
 * file so the Booking Service can store media in object storage and associate
 * the references with the booking (Requirement 7.2).
 */
export async function createBooking(payload: CreateBookingPayload): Promise<CreateBookingResponse> {
  const form = new FormData();
  const { media, ...rest } = payload;
  form.append('payload', new Blob([JSON.stringify(rest)], { type: 'application/json' }));
  for (const file of media) {
    form.append('media', file, file.name);
  }

  const { data } = await apiClient.post<CreateBookingResponse>('/bookings', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
  return data;
}
