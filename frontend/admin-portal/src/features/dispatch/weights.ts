import type { DispatchWeights } from './api';

/** Ordered weight fields with display labels for the configuration UI. */
export const WEIGHT_FIELDS: ReadonlyArray<{ key: keyof DispatchWeights; label: string }> = [
  { key: 'distance', label: 'Distance' },
  { key: 'availability', label: 'Availability' },
  { key: 'rating', label: 'Rating' },
  { key: 'skill', label: 'Skill match' },
  { key: 'performance', label: 'Performance' },
];

/**
 * Tolerance for the "sum equals 1.0" check. Requirement 19.5 requires the
 * weights to sum to exactly 1.0; because the inputs are decimals we accept a
 * tiny floating-point epsilon so e.g. 0.1 + 0.2 comparisons don't spuriously
 * fail. Values are rounded before submission so the server receives a clean sum.
 */
export const SUM_EPSILON = 1e-9;

/** Sum of all five weights. */
export function weightsSum(weights: DispatchWeights): number {
  return WEIGHT_FIELDS.reduce((total, field) => total + (weights[field.key] || 0), 0);
}

export interface WeightValidation {
  /** True when every weight is within 0.0–1.0 and the sum equals 1.0. */
  isValid: boolean;
  /** The current sum, for display. */
  sum: number;
  /** Which weights fall outside the 0.0–1.0 range. */
  outOfRange: Array<keyof DispatchWeights>;
  /** True when the sum is not within epsilon of 1.0. */
  sumInvalid: boolean;
  /** Human-readable summary of the first failing condition, if any. */
  message: string | null;
}

/**
 * Validate the matching weights against Requirement 19.5: each individual
 * weight must be in 0.0–1.0 and all weights must sum to exactly 1.0. The
 * message identifies which condition failed (mirroring the server error).
 */
export function validateWeights(weights: DispatchWeights): WeightValidation {
  const outOfRange = WEIGHT_FIELDS.map((field) => field.key).filter((key) => {
    const value = weights[key];
    return !Number.isFinite(value) || value < 0 || value > 1;
  });

  const sum = weightsSum(weights);
  const sumInvalid = Math.abs(sum - 1) > SUM_EPSILON;

  let message: string | null = null;
  if (outOfRange.length > 0) {
    message = 'Each weight must be between 0.0 and 1.0.';
  } else if (sumInvalid) {
    message = `Weights must sum to exactly 1.0 (currently ${sum.toFixed(2)}).`;
  }

  return {
    isValid: outOfRange.length === 0 && !sumInvalid,
    sum,
    outOfRange,
    sumInvalid,
    message,
  };
}
