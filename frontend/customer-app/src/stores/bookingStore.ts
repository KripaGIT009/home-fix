import { create } from 'zustand';

/**
 * Booking lifecycle states mirror the Booking_Service state machine
 * (Requirement 9). The client tracks these to drive the tracking UI.
 */
export type BookingStatus =
  | 'CREATED'
  | 'SEARCHING_PROVIDER'
  | 'SEARCHING_FAILED'
  | 'PROVIDER_ASSIGNED'
  | 'PROVIDER_ACCEPTED'
  | 'PROVIDER_ON_THE_WAY'
  | 'PROVIDER_ARRIVED'
  | 'JOB_STARTED'
  | 'JOB_PAUSED'
  | 'ADDITIONAL_QUOTE_REQUIRED'
  | 'CUSTOMER_APPROVAL_PENDING'
  | 'JOB_COMPLETED'
  | 'CUSTOMER_CONFIRMED'
  | 'PAYMENT_PENDING'
  | 'PAYMENT_COMPLETED'
  | 'DISPUTED'
  | 'REFUNDED'
  | 'CANCELLED';

export interface ActiveBooking {
  bookingId: string;
  referenceNumber: string;
  status: BookingStatus;
  subcategoryId: string;
  addressId: string;
  isEmergency: boolean;
  /** Assigned provider summary, populated once a provider accepts. */
  provider?: {
    id: string;
    displayName: string;
    verified: boolean;
    rating: number;
    etaMinutes?: number;
  };
}

interface BookingState {
  activeBooking: ActiveBooking | null;

  setActiveBooking: (booking: ActiveBooking) => void;
  updateStatus: (status: BookingStatus) => void;
  updateProvider: (provider: NonNullable<ActiveBooking['provider']>) => void;
  clearActiveBooking: () => void;
}

export const useBookingStore = create<BookingState>((set, get) => ({
  activeBooking: null,

  setActiveBooking: (booking) => set({ activeBooking: booking }),

  updateStatus: (status) => {
    const current = get().activeBooking;
    if (!current) return;
    set({ activeBooking: { ...current, status } });
  },

  updateProvider: (provider) => {
    const current = get().activeBooking;
    if (!current) return;
    set({ activeBooking: { ...current, provider } });
  },

  clearActiveBooking: () => set({ activeBooking: null }),
}));
