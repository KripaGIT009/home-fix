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
  ESCALATED: 'error',
  REFUND_FAILED: 'error',
  FAILED: 'error',
  SUSPENDED: 'error',
  DEACTIVATED: 'error',
  REJECTED: 'error',
  CANCELLED: 'error',
  DISPUTED: 'error',
  REFUNDED: 'secondary',
  PARTIALLY_REFUNDED: 'secondary',
  CLOSED: 'default',
  EXPIRED: 'default',
  INACTIVE: 'default',
};

interface StatusChipProps {
  /**
   * Null/absent when the owning service does not know it (e.g. a provider with
   * no verification record yet); rendered as a plain dash rather than a chip.
   */
  status: string | null | undefined;
}

/** A small coloured chip that renders a domain status consistently. */
export function StatusChip({ status }: StatusChipProps) {
  if (!status) return <>—</>;
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
