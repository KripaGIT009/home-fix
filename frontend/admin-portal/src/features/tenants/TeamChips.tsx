import { Chip, Rating, Stack, Tooltip, Typography } from '@mui/material';
import { notAssignableReason, type TeamProvider } from './model';

/**
 * Small presentational pieces for a Tenant's team, shared by the platform
 * Members dialog, the Tenant Portal's Team screen and the assign dialog, so a
 * Provider reads the same everywhere (Requirements MT-3.4, MT-8.4).
 */

/** Whether the Provider's availability slots cover the current moment. */
export function AvailabilityChip({ availableNow }: { availableNow: boolean }) {
  return availableNow ? (
    <Chip size="small" color="success" label="Available now" />
  ) : (
    <Chip size="small" variant="outlined" label="Not available now" />
  );
}

/**
 * Whether the Provider may be assigned a job. The reason shown for a refusal
 * is a hint derived from the verification status; provider-service's
 * `assignable` flag alone decides (APPROVED and not under review).
 */
export function AssignableChip({ provider }: { provider: TeamProvider }) {
  if (provider.assignable) {
    return <Chip size="small" color="primary" variant="outlined" label="Assignable" />;
  }
  return (
    <Tooltip title={notAssignableReason(provider)}>
      <Chip size="small" color="warning" variant="outlined" label="Not assignable" />
    </Tooltip>
  );
}

/** Star rating with its numeric value, or a dash for an unrated Provider. */
export function RatingCell({ rating }: { rating: number | null | undefined }) {
  if (rating == null) return <>—</>;
  return (
    <Stack direction="row" spacing={0.5} alignItems="center">
      <Rating value={rating} precision={0.1} size="small" readOnly />
      <Typography variant="caption" color="text.secondary">
        {rating.toFixed(1)}
      </Typography>
    </Stack>
  );
}
