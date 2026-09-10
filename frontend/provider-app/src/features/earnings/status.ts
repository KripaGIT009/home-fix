import type { SettlementStatus } from './api';

type StatusColor = 'default' | 'info' | 'primary' | 'success' | 'warning' | 'error';

/** Display label + colour for a settlement status (Requirement 14.3). */
export function describeSettlementStatus(status: SettlementStatus): {
  label: string;
  color: StatusColor;
} {
  switch (status) {
    case 'PENDING':
      return { label: 'Pending', color: 'default' };
    case 'PROCESSING':
      return { label: 'Processing', color: 'info' };
    case 'COMPLETED':
      return { label: 'Completed', color: 'success' };
    case 'FAILED':
      return { label: 'Failed', color: 'error' };
    default:
      return { label: status, color: 'default' };
  }
}
