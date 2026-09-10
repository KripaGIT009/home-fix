import { apiClient } from '@api/client';

/**
 * Coupon Management bindings (Requirement 19.2, Requirement 21).
 *
 * Admins create coupons and deactivate them. Coupon attributes and validation
 * mirror Requirement 21.1: unique 4–20 char alphanumeric code (case-insensitive),
 * FLAT or PERCENTAGE discount, discount value > 0, min order value ≥ 0, a max
 * discount cap (required for PERCENTAGE), a valid_from/expiry range (expiry after
 * valid_from), and per-user + total usage limits (each ≥ 1).
 *
 * Endpoints (see design.md — Admin Service / Promotion & Coupon Service):
 * - GET   /admin/coupons                    — list coupons
 * - POST  /admin/coupons                    — create a coupon
 * - PATCH /admin/coupons/{id}/deactivate    — deactivate a coupon
 */

export type DiscountType = 'FLAT' | 'PERCENTAGE';
export type CouponStatus = 'ACTIVE' | 'INACTIVE' | 'EXPIRED';

export interface Coupon {
  id: string;
  code: string;
  discountType: DiscountType;
  discountValue: number;
  minOrderValue: number;
  maxDiscountCap?: number;
  validFrom: string;
  expiryDate: string;
  perUserLimit: number;
  totalLimit: number;
  totalRedeemed: number;
  status: CouponStatus;
}

export interface CreateCouponPayload {
  code: string;
  discountType: DiscountType;
  discountValue: number;
  minOrderValue: number;
  maxDiscountCap?: number;
  validFrom: string;
  expiryDate: string;
  perUserLimit: number;
  totalLimit: number;
}

/** GET /admin/coupons — all coupons. */
export async function fetchCoupons(): Promise<Coupon[]> {
  const { data } = await apiClient.get<Coupon[]>('/admin/coupons');
  return data;
}

/** POST /admin/coupons — create a coupon. */
export async function createCoupon(payload: CreateCouponPayload): Promise<Coupon> {
  const { data } = await apiClient.post<Coupon>('/admin/coupons', payload);
  return data;
}

/** PATCH /admin/coupons/{id}/deactivate — deactivate a coupon (Req 21.4). */
export async function deactivateCoupon(id: string): Promise<Coupon> {
  const { data } = await apiClient.patch<Coupon>(`/admin/coupons/${id}/deactivate`);
  return data;
}
