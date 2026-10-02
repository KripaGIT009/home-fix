import { create } from 'zustand';
import type { AddressFormValues } from './schemas';

/**
 * Draft of an in-progress booking request, shared between the Service Request
 * screen and the Price Estimate screen (which are separate routes).
 *
 * Media files are intentionally NOT persisted anywhere (they are large File
 * objects) — they live only in this in-memory store for the duration of the
 * flow. The draft is cleared once a booking is created or the flow is
 * abandoned.
 */
export interface BookingDraft {
  /**
   * The parent category of `subcategoryId`. The Booking Service requires it on
   * creation (`@NotNull categoryId`) and does not derive it from the
   * subcategory, so it is carried from the catalog entry the customer picked
   * rather than looked up again at confirmation time.
   */
  categoryId: string;
  subcategoryId: string;
  isEmergency: boolean;
  /** Omitted for emergency bookings. */
  scheduledAt?: string;
  address: AddressFormValues;
  description?: string;
  media: File[];
}

interface BookingDraftState {
  draft: BookingDraft | null;
  setDraft: (draft: BookingDraft) => void;
  clearDraft: () => void;
}

export const useBookingDraftStore = create<BookingDraftState>((set) => ({
  draft: null,
  setDraft: (draft) => set({ draft }),
  clearDraft: () => set({ draft: null }),
}));
