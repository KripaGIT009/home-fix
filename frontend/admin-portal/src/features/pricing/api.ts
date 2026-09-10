import { apiClient } from '@api/client';

/**
 * Pricing Configuration bindings (Requirement 6.11). Admins configure pricing
 * parameters per Service_Subcategory; updates apply to new bookings within 60s.
 *
 * Endpoints (see design.md — Pricing Engine / Admin Service):
 * - GET /admin/pricing/config                 — all subcategory pricing configs
 * - PUT /admin/pricing/config/{subcategoryId} — update one subcategory's config
 */

/** Configurable pricing parameters for one service subcategory (Req 6.11). */
export interface PricingConfig {
  subcategoryId: string;
  subcategoryName: string;
  categoryName: string;
  basePrice: number;
  perKmRate: number;
  maxTravelCharge: number;
  platformFeePercent: number;
  nightSurcharge: number;
  weekendSurcharge: number;
  emergencyMultiplierCap: number;
  surgeMultiplierCap: number;
  currency: string;
}

/** GET /admin/pricing/config — pricing configuration for every subcategory. */
export async function fetchPricingConfigs(): Promise<PricingConfig[]> {
  const { data } = await apiClient.get<PricingConfig[]>('/admin/pricing/config');
  return data;
}

/** PUT /admin/pricing/config/{subcategoryId} — update one subcategory config. */
export async function updatePricingConfig(config: PricingConfig): Promise<PricingConfig> {
  const { data } = await apiClient.put<PricingConfig>(
    `/admin/pricing/config/${config.subcategoryId}`,
    config,
  );
  return data;
}
