import { apiClient } from '@api/client';

/**
 * Pricing Configuration bindings (Requirement 6.11). Admins configure pricing
 * parameters per Service_Subcategory; updates apply to new bookings within 60s.
 *
 * Endpoints (see design.md — Pricing Engine / Admin Service):
 * - GET /admin/pricing/config                 — all subcategory pricing configs
 * - PUT /admin/pricing/config/{subcategoryId} — update one subcategory's config
 */

/**
 * Configurable pricing parameters for one service subcategory (Req 6.11).
 *
 * The catalog names live in the Service Catalog, not the Pricing Engine, so
 * they may come back null or absent; the screen falls back to the subcategory
 * id. Currency falls back to INR.
 */
export interface PricingConfig {
  subcategoryId: string;
  subcategoryName?: string | null;
  categoryName?: string | null;
  basePrice: number;
  perKmRate: number;
  maxTravelCharge: number;
  platformFeePercent: number;
  nightSurcharge: number;
  weekendSurcharge: number;
  emergencyMultiplierCap: number;
  surgeMultiplierCap: number;
  currency?: string | null;
}

/** The parameters an admin edits; everything else on the config is read-only. */
export type PricingConfigUpdate = Pick<
  PricingConfig,
  | 'basePrice'
  | 'perKmRate'
  | 'maxTravelCharge'
  | 'platformFeePercent'
  | 'nightSurcharge'
  | 'weekendSurcharge'
  | 'emergencyMultiplierCap'
  | 'surgeMultiplierCap'
>;

/** Display label for a config's subcategory, falling back to its id. */
export function subcategoryLabel(config: PricingConfig): string {
  return config.subcategoryName ?? config.subcategoryId;
}

/** GET /admin/pricing/config — pricing configuration for every subcategory. */
export async function fetchPricingConfigs(): Promise<PricingConfig[]> {
  const { data } = await apiClient.get<PricingConfig[]>('/admin/pricing/config');
  return data;
}

/**
 * PUT /admin/pricing/config/{subcategoryId} — update one subcategory config.
 *
 * Sends only the editable parameters: the Pricing Engine merges them over the
 * stored config, keeping fields this screen does not manage (tax rate, override
 * floor/ceiling), and the display-only names are not its to store.
 */
export async function updatePricingConfig(
  subcategoryId: string,
  update: PricingConfigUpdate,
): Promise<PricingConfig> {
  const { data } = await apiClient.put<PricingConfig>(
    `/admin/pricing/config/${subcategoryId}`,
    update,
  );
  return data;
}
