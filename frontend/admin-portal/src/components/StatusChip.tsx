import { Chip } from '@mui/material';

type ChipColor = 'default' | 'success' | 'warning' | 'error' | 'info' | 'primary' | 'secondary';

/** Map common domain statuses to a sensible chip colour. */
const STATUS_COLORS: Record<string, ChipColor> = {
  ACTIVE: 'success',
  APPROVED: 'success',
  COMPLETED: 'success',
  PAYMENT_COMPLETED: 'success',
  PUBLISHED: 'success',
  RESOLVED: 'success',
  ONLINE: 'success',
  PENDING: 'warning',
  DOCUMENT_SUBMITTED: 'warning',
  OPEN: 'warning',
  IN_PROGRESS: 'info',
  SEARCHING_PROVIDER: 'info',
  FLAGGED: 'warning',
  SUSPENDED: 'error',
  DEACTIVATED: 'error',
  REJECTED: 'error',
  CANCELLED: 'error',
  DISPUTED: 'error',
  REFUNDED: 'secondary',
  EXPIRED: 'default',
  INACTIVE: 'default',
};

interface StatusChipProps {
  status: string;
}

/** A small coloured chip that renders a domain status consistently. */
export function StatusChip({ status }: StatusChipProps) {
  const color = STATUS_COLORS[status] ?? 'default';
  const label = status.replace(/_/g, ' ').toLowerCase();
  return (
    <Chip
      size="small"
      color={color}
      variant={color === 'default' ? 'outlined' : 'filled'}
      label={label}
      sx={{ textTransform: 'capitalize' }}
    />
  );
}
