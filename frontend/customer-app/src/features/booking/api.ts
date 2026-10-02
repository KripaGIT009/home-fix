import { ApiError, apiClient } from '@api/client';
import { useAuthStore } from '@stores/authStore';
import type { AddressFormValues } from './schemas';
import { findSavedAddress, useSavedAddressStore } from './savedAddresses';

/**
 * Pricing Engine + Booking Service API bindings (Requirements 6, 7, 8).
 *
 * Endpoints (see design.md — Pricing Engine and Booking Service, routed via
 * the API Gateway):
 * - POST /pricing/estimate  — itemized price estimate for a prospective booking
 * - POST /bookings          — create a booking (JSON)
 * - POST /bookings/media    — create a booking with media (multipart)
 * - POST /bookings/{reference}/confirmation — confirm a scheduled booking
 * - POST /customers/{id}/addresses — save the service address the booking needs
 *
 * All flow through the shared Axios client, inheriting the Authorization and
 * X-Correlation-ID interceptors and the normalized ApiError rejection.
 */

/** A single line item in the itemized price breakdown (Requirement 6.9). */
export interface PriceLineItem {
  /** Stable component key, e.g. "basePrice", "emergencyCharge". */
  key: string;
  /** Human-readable label. */
  label: string;
  /** Signed amount — negative for discounts and coupons. */
  amount: number;
}

/**
 * The Pricing Engine's response to POST /pricing/estimate.
 *
 * This mirrors the service's `PriceBreakdownDto` exactly: a flat set of named
 * components that sum to `total`. It deliberately does NOT match design.md's
 * richer shape (`estimateId`, `currency`, `lineItems[]`) — the service has
 * never returned that, and the screen used to crash on `lineItems.map` of an
 * undefined. Presentation concerns are derived in `toPriceEstimate` below
 * rather than asked of the service.
 */
interface PriceBreakdownResponse {
  basePrice: number;
  distanceCharge: number;
  timeCharge: number;
  partsMaterialsCharge: number;
  emergencyCharge: number;
  weekendSurcharge: number;
  nightSurcharge: number;
  demandSurgeCharge: number;
  platformFee: number;
  taxes: number;
  discountAmount: number;
  couponAmount: number;
  total: number;
}

/** Itemized estimate as the UI consumes it (Requirements 6.9, 7.3). */
export interface PriceEstimate {
  currency: string;
  lineItems: PriceLineItem[];
  total: number;
  /** Present when a coupon actually reduced the price. */
  appliedCoupon?: {
    code: string;
    discountAmount: number;
  };
}

/**
 * The stack is rupee-only today: the Pricing Engine holds no currency on its
 * parameters and every amount it returns is INR. Kept as one constant so the
 * day it becomes a server concern there is a single place to take it from.
 */
const CURRENCY = 'INR';

/** Order and labels for the breakdown, as the customer should read it. */
const LINE_ITEMS: ReadonlyArray<{ key: keyof PriceBreakdownResponse; label: string }> = [
  { key: 'basePrice', label: 'Base price' },
  { key: 'distanceCharge', label: 'Distance' },
  { key: 'timeCharge', label: 'Labour' },
  { key: 'partsMaterialsCharge', label: 'Parts and materials' },
  { key: 'emergencyCharge', label: 'Emergency callout' },
  { key: 'weekendSurcharge', label: 'Weekend surcharge' },
  { key: 'nightSurcharge', label: 'Night surcharge' },
  { key: 'demandSurgeCharge', label: 'High demand' },
  { key: 'platformFee', label: 'Platform fee' },
  { key: 'taxes', label: 'Taxes' },
  { key: 'discountAmount', label: 'Discount' },
  { key: 'couponAmount', label: 'Coupon' },
];

/** Components the service reports as a positive number but which reduce the total. */
const DEDUCTIONS: ReadonlySet<keyof PriceBreakdownResponse> = new Set([
  'discountAmount',
  'couponAmount',
]);

/**
 * Turn the service's flat breakdown into the itemized view the screen renders.
 *
 * Zero components are dropped so the customer sees only what they are actually
 * being charged for, and deductions are negated so the list sums to `total`.
 */
function toPriceEstimate(breakdown: PriceBreakdownResponse, couponCode?: string): PriceEstimate {
  const lineItems: PriceLineItem[] = [];
  for (const { key, label } of LINE_ITEMS) {
    const raw = Number(breakdown[key] ?? 0);
    if (raw === 0) continue;
    lineItems.push({ key, label, amount: DEDUCTIONS.has(key) ? -raw : raw });
  }

  const couponAmount = Number(breakdown.couponAmount ?? 0);
  return {
    currency: CURRENCY,
    lineItems,
    total: Number(breakdown.total ?? 0),
    // Only report a coupon as applied when it actually took money off; the
    // service returns 0 for a code that matched nothing chargeable.
    ...(couponCode && couponAmount > 0
      ? { appliedCoupon: { code: couponCode, discountAmount: couponAmount } }
      : {}),
  };
}

/**
 * What the caller wants priced. Mapped to the service's field names in
 * `requestEstimate` rather than matching them here, so the screens keep
 * speaking the UI's own vocabulary.
 */
export interface EstimateRequestPayload {
  subcategoryId: string;
  isEmergency: boolean;
  /** ISO-8601 instant. Omitted for emergency bookings (dispatched immediately). */
  scheduledAt?: string;
  address: AddressFormValues;
  /** Optional coupon code to validate and apply (Requirement 6.10). */
  couponCode?: string;
}

/**
 * Render an instant as the wall-clock time the Pricing Engine expects.
 *
 * `scheduledLocalTime` is a `LocalDateTime`, and it is what decides the night
 * and weekend surcharges, so it has to be the customer's local time rather
 * than UTC. A trailing `Z` would fail to parse.
 */
function toLocalDateTime(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const pad = (n: number) => String(n).padStart(2, '0');
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}` +
    `T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  );
}

/**
 * POST /pricing/estimate — request an itemized estimate.
 *
 * Only the fields the service declares are sent. `address` is deliberately not
 * among them: the Pricing Engine takes a `distanceKm`, not an address, and has
 * no geocoder — so distance is left at zero rather than invented here. The
 * estimate is therefore exclusive of travel, which the screen already frames
 * as an estimate.
 *
 * A 503 from the Pricing Engine surfaces as an ApiError with status 503; the
 * UI must not proceed to booking creation in that case (Requirement 7.4).
 * A rejected coupon surfaces as a 4xx ApiError whose message identifies the
 * violated constraint (Requirement 6.10).
 */
export async function requestEstimate(payload: EstimateRequestPayload): Promise<PriceEstimate> {
  const { data } = await apiClient.post<PriceBreakdownResponse>('/pricing/estimate', {
    subcategoryId: payload.subcategoryId,
    emergency: payload.isEmergency,
    ...(payload.scheduledAt ? { scheduledLocalTime: toLocalDateTime(payload.scheduledAt) } : {}),
    ...(payload.couponCode ? { couponCode: payload.couponCode } : {}),
  });
  return toPriceEstimate(data, payload.couponCode);
}

export interface CreateBookingPayload {
  categoryId: string;
  subcategoryId: string;
  isEmergency: boolean;
  scheduledAt?: string;
  address: AddressFormValues;
  description?: string;
  // No estimateId: the Booking Service has no such concept. It re-prices the
  // booking itself and returns its own estimate, so there is nothing to echo --
  // but it needs the coupon to re-price with the same discount.
  couponCode?: string;
  /** Validated media files to upload with the booking (Requirement 7.2). */
  media: File[];
}

/**
 * Response after creating a booking (Requirements 7.1, 8.1).
 *
 * Field names mirror the Booking Service's `BookingResponse` exactly. They used
 * to be spelled `referenceNumber`/`isEmergency` here, which the service has
 * never returned: both read as `undefined`, so the reference went unrecorded
 * and every booking took the non-emergency branch after creation.
 */
export interface CreateBookingResponse {
  bookingId: string;
  reference: string;
  status: string;
  emergency: boolean;
}

/**
 * Code of the error raised, before any request is made, when the entered
 * address has no coordinates and so cannot become a saved address. It is an
 * ApiError so the screens surface it the same way as a server rejection.
 */
export const ADDRESS_COORDINATES_REQUIRED = 'ADDRESS_COORDINATES_REQUIRED';

/**
 * Turn the entered address into a saved address id.
 *
 * Every booking needs one: the Dispatch Engine finds the service location only
 * through the Customer Service's saved address, and a booking without one is
 * dead-lettered after confirmation -- accepted, but never dispatched. Nothing
 * here may fall through to a booking with no address; the Booking Service now
 * rejects one as well.
 *
 * The Customer Service stores addresses as a label plus coordinates; it
 * requires `lat`/`lng` and there is no forward geocoder anywhere in the stack.
 * A hand-typed address without coordinates is therefore refused here (the
 * request form already blocks it; this is the backstop).
 *
 * An address this device already saved for the same place is reused rather
 * than saved again: the service caps a customer at 10 addresses, and saving one
 * per booking used to exhaust that cap. A failure to save is surfaced to the
 * caller rather than swallowed.
 */
async function resolveAddressId(
  customerId: string | undefined,
  address: AddressFormValues,
): Promise<string> {
  if (!customerId) {
    throw new ApiError({
      status: 401,
      code: 'UNAUTHENTICATED',
      message: 'Your session has ended. Please sign in again.',
    });
  }
  const { latitude, longitude } = address;
  if (latitude === undefined || longitude === undefined) {
    throw new ApiError({
      status: 0,
      code: ADDRESS_COORDINATES_REQUIRED,
      message:
        'We need your exact location to send a pro. Tap "Use my location" or pick one of ' +
        'your saved addresses, then try again.',
    });
  }

  const saved = findSavedAddress(customerId, address);
  if (saved) return saved.addressId;

  // `label` is capped at 100 characters by the service.
  const label = [address.line1, address.line2, address.city, address.postalCode]
    .filter((part): part is string => Boolean(part && part.trim()))
    .join(', ')
    .slice(0, 100);

  const { data } = await apiClient.post<{ addressId: string }>(
    `/customers/${customerId}/addresses`,
    { label, lat: latitude, lng: longitude },
  );
  useSavedAddressStore.getState().remember(customerId, {
    addressId: data.addressId,
    address: { ...address, latitude, longitude },
  });
  return data.addressId;
}

/**
 * POST /bookings — create a booking, with media when the customer attached any.
 *
 * The Booking Service exposes two creation endpoints and they are not
 * interchangeable: `POST /bookings` consumes `application/json`, and multipart
 * lives on `POST /bookings/media`, which takes flat request parameters rather
 * than a JSON part. Posting multipart to `/bookings` is what produced the
 * 415 this replaces.
 *
 * `couponCode` is sent to both: the service re-prices the booking itself, and
 * without the code it charged the undiscounted total while the estimate screen
 * showed the discount.
 *
 * A scheduled booking comes back in CREATED and is not dispatched until it is
 * confirmed (see `confirmBooking`); an emergency booking is already searching.
 */
export async function createBooking(payload: CreateBookingPayload): Promise<CreateBookingResponse> {
  const {
    media,
    address,
    categoryId,
    subcategoryId,
    isEmergency,
    scheduledAt,
    description,
    couponCode,
  } = payload;
  const addressId = await resolveAddressId(useAuthStore.getState().user?.id, address);

  if (media.length === 0) {
    const { data } = await apiClient.post<CreateBookingResponse>('/bookings', {
      categoryId,
      subcategoryId,
      addressId,
      emergency: isEmergency,
      ...(scheduledAt ? { scheduledAt } : {}),
      ...(description ? { description } : {}),
      ...(couponCode ? { couponCode } : {}),
    });
    return data;
  }

  const form = new FormData();
  form.append('categoryId', categoryId);
  form.append('subcategoryId', subcategoryId);
  form.append('addressId', addressId);
  form.append('emergency', String(isEmergency));
  if (scheduledAt) form.append('scheduledAt', scheduledAt);
  if (description) form.append('description', description);
  if (couponCode) form.append('couponCode', couponCode);
  for (const file of media) {
    form.append('media', file, file.name);
  }

  // This override is load-bearing. The shared client defaults every request to
  // `application/json`, and axios reacts to a JSON content type on a FormData
  // body by serializing it through `formDataToJSON` -- the files would be
  // dropped and the server would receive a JSON object it cannot bind. Naming
  // multipart here avoids that path; the XHR adapter then clears the header so
  // the browser sets it again with the boundary it alone can generate.
  const { data } = await apiClient.post<CreateBookingResponse>('/bookings/media', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
  return data;
}

/**
 * POST /bookings/{reference}/confirmation — the customer accepts the estimate
 * for a scheduled booking (Requirement 7.5).
 *
 * This is what moves the booking from CREATED to SEARCHING_PROVIDER and
 * publishes the BookingCreated event dispatch listens for; a scheduled booking
 * that is created but never confirmed is never offered to a provider. Addressed
 * by the booking reference, not its id. Emergency bookings skip this step: the
 * service confirms them as part of creation (Requirement 8.1).
 */
export async function confirmBooking(reference: string): Promise<CreateBookingResponse> {
  const { data } = await apiClient.post<CreateBookingResponse>(
    `/bookings/${encodeURIComponent(reference)}/confirmation`,
  );
  return data;
}
