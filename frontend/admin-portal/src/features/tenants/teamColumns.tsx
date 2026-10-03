import { Button, Stack, Typography } from '@mui/material';
import type { Column } from '@components/DataTable';
import { StatusChip } from '@components/StatusChip';
import { formatMobileNumber } from '@features/auth/phone';
import { shortId } from '@lib/format';
import type { TeamProvider } from './model';
import { AssignableChip, AvailabilityChip, RatingCell } from './TeamChips';

/**
 * The team table's columns (Requirement MT-3.4: name, primary skill,
 * verification status, rating, availability now), shared by the Tenant
 * Portal's Team screen and the platform Members dialog. Passing `onRemove`
 * adds the remove action.
 */
export function teamProviderColumns(onRemove?: (provider: TeamProvider) => void) {
  const columns: Column<TeamProvider>[] = [
    {
      key: 'name',
      header: 'Provider',
      render: (row) => (
        <Stack spacing={0.25}>
          <Typography variant="body2" fontWeight={600} noWrap>
            {row.displayName ?? shortId(row.providerId)}
          </Typography>
          {row.mobileNumber ? (
            <Typography variant="caption" color="text.secondary" noWrap>
              {formatMobileNumber(row.mobileNumber)}
            </Typography>
          ) : null}
        </Stack>
      ),
    },
    { key: 'skill', header: 'Primary skill', render: (row) => row.primarySkill ?? '—' },
    {
      key: 'verification',
      header: 'Verification',
      render: (row) => <StatusChip status={row.verificationStatus} />,
    },
    { key: 'rating', header: 'Rating', render: (row) => <RatingCell rating={row.rating} /> },
    {
      key: 'available',
      header: 'Availability',
      render: (row) => <AvailabilityChip availableNow={row.availableNow} />,
    },
    { key: 'assignable', header: 'Jobs', render: (row) => <AssignableChip provider={row} /> },
  ];
  if (onRemove) {
    columns.push({
      key: 'action',
      header: '',
      align: 'right',
      render: (row) => (
        <Button size="small" color="error" variant="outlined" onClick={() => onRemove(row)}>
          Remove
        </Button>
      ),
    });
  }
  return columns;
}
