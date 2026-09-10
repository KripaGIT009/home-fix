import { apiClient } from '@api/client';

/**
 * Dispatch Rule Configuration bindings (Requirement 8.4 / 19.5).
 *
 * The Dispatch Engine scores providers as:
 *   distanceScore × distance + availabilityScore × availability +
 *   ratingScore × rating + skillScore × skill + performanceScore × performance
 * with the five weights summing to exactly 1.0 (defaults 0.30/0.25/0.20/0.15/0.10).
 *
 * Endpoints (see design.md — Dispatch Engine / Admin Service):
 * - GET /admin/dispatch/config — current weights + radius parameters
 * - PUT /admin/dispatch/config — update weights (validated server-side too)
 */

/** The five matching weights. Keys mirror Requirement 8.3 score components. */
export interface DispatchWeights {
  distance: number;
  availability: number;
  rating: number;
  skill: number;
  performance: number;
}

export interface DispatchConfig {
  weights: DispatchWeights;
  /** Initial search radius in km (default 10). */
  initialRadiusKm: number;
  /** Radius expansion increment in km (default 5). */
  radiusIncrementKm: number;
  /** Maximum number of radius expansion cycles (default 3). */
  maxExpansionCycles: number;
  /** Job-offer acceptance timeout in seconds (default 60). */
  offerTimeoutSeconds: number;
}

/** GET /admin/dispatch/config — current dispatch configuration. */
export async function fetchDispatchConfig(): Promise<DispatchConfig> {
  const { data } = await apiClient.get<DispatchConfig>('/admin/dispatch/config');
  return data;
}

/** PUT /admin/dispatch/config — persist updated dispatch configuration. */
export async function updateDispatchConfig(config: DispatchConfig): Promise<DispatchConfig> {
  const { data } = await apiClient.put<DispatchConfig>('/admin/dispatch/config', config);
  return data;
}
