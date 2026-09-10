import { useState } from 'react';
import {
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Divider,
  Skeleton,
  Stack,
  Typography,
} from '@mui/material';
import { AppShell } from '@components/AppShell';
import { QueryStateView } from '@components/QueryStateView';
import { formatCurrency, formatDate, formatDateTime } from '@lib/format';
import { SettlementForm } from './SettlementForm';
import { describeSettlementStatus } from './status';
import { useEarningsHistory, useSettlementInfo, useSettlements } from './hooks';
import type { EarningsHistoryItem, SettlementRequest } from './api';

const PAGE_SIZE = 10;

/**
 * Earnings & Settlement History screen (Requirement 14, 28.8): a paginated
 * per-job earnings history showing gross/fee/net, a settlement request form
 * (validated against available balance + verified bank account), and the
 * Provider's settlement request history.
 */
export function EarningsScreen() {
  const [page, setPage] = useState(1);
  const history = useEarningsHistory(page, PAGE_SIZE);
  const settlementInfo = useSettlementInfo();
  const settlements = useSettlements();

  const totalPages = history.data?.totalPages ?? 1;

  return (
    <AppShell title="Earnings">
      <Stack spacing={2}>
        <Typography variant="subtitle1" fontWeight={700}>
          Request a settlement
        </Typography>
        {settlementInfo.isLoading ? (
          <Skeleton variant="rounded" height={160} />
        ) : (
          <Card variant="outlined">
            <CardContent>
              <QueryStateView
                isLoading={false}
                isError={settlementInfo.isError}
                error={settlementInfo.error}
                onRetry={() => void settlementInfo.refetch()}
              >
                {settlementInfo.data ? <SettlementForm info={settlementInfo.data} /> : null}
              </QueryStateView>
            </CardContent>
          </Card>
        )}

        <Typography variant="subtitle1" fontWeight={700} sx={{ mt: 1 }}>
          Settlement history
        </Typography>
        <QueryStateView
          isLoading={settlements.isLoading}
          isError={settlements.isError}
          error={settlements.error}
          onRetry={() => void settlements.refetch()}
          isEmpty={(settlements.data?.length ?? 0) === 0}
          emptyMessage="No settlement requests yet."
        >
          <Stack spacing={1}>
            {settlements.data?.map((settlement) => (
              <SettlementRow key={settlement.id} settlement={settlement} />
            ))}
          </Stack>
        </QueryStateView>

        <Typography variant="subtitle1" fontWeight={700} sx={{ mt: 1 }}>
          Earnings by job
        </Typography>
        <QueryStateView
          isLoading={history.isLoading}
          isError={history.isError}
          error={history.error}
          onRetry={() => void history.refetch()}
          isEmpty={(history.data?.items.length ?? 0) === 0}
          emptyMessage="No completed jobs yet. Your earnings will appear here."
        >
          <Stack spacing={1}>
            {history.data?.items.map((item) => (
              <EarningsRow key={item.bookingId} item={item} />
            ))}
          </Stack>

          <Box
            sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mt: 1 }}
          >
            <Button
              size="small"
              onClick={() => setPage((current) => Math.max(1, current - 1))}
              disabled={page <= 1 || history.isFetching}
            >
              Previous
            </Button>
            <Typography variant="caption" color="text.secondary" aria-live="polite">
              Page {page} of {totalPages}
            </Typography>
            <Button
              size="small"
              onClick={() => setPage((current) => Math.min(totalPages, current + 1))}
              disabled={page >= totalPages || history.isFetching}
            >
              Next
            </Button>
          </Box>
        </QueryStateView>
      </Stack>
    </AppShell>
  );
}

function EarningsRow({ item }: { item: EarningsHistoryItem }) {
  return (
    <Card variant="outlined">
      <CardContent sx={{ '&:last-child': { pb: 2 } }}>
        <Stack direction="row" justifyContent="space-between" alignItems="flex-start" spacing={1}>
          <Stack spacing={0.25}>
            <Typography variant="subtitle2" fontWeight={700}>
              {item.serviceName}
            </Typography>
            <Typography variant="caption" color="text.secondary">
              {item.reference} · {formatDate(item.creditedAt)}
            </Typography>
          </Stack>
          <Typography variant="subtitle1" fontWeight={700}>
            {formatCurrency(item.net, item.currency)}
          </Typography>
        </Stack>
        <Divider sx={{ my: 1 }} />
        <Stack direction="row" justifyContent="space-between">
          <Typography variant="caption" color="text.secondary">
            Gross {formatCurrency(item.gross, item.currency)}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            Fee −{formatCurrency(item.platformFee, item.currency)}
          </Typography>
          <Typography variant="caption" fontWeight={700}>
            Net {formatCurrency(item.net, item.currency)}
          </Typography>
        </Stack>
      </CardContent>
    </Card>
  );
}

function SettlementRow({ settlement }: { settlement: SettlementRequest }) {
  const status = describeSettlementStatus(settlement.status);
  return (
    <Card variant="outlined">
      <CardContent sx={{ '&:last-child': { pb: 2 } }}>
        <Stack direction="row" justifyContent="space-between" alignItems="center" spacing={1}>
          <Stack spacing={0.25}>
            <Typography variant="subtitle2" fontWeight={700}>
              {formatCurrency(settlement.amount, settlement.currency)}
            </Typography>
            <Typography variant="caption" color="text.secondary">
              Requested {formatDateTime(settlement.requestedAt)}
              {settlement.completedAt
                ? ` · Completed ${formatDateTime(settlement.completedAt)}`
                : ''}
            </Typography>
          </Stack>
          <Chip size="small" label={status.label} color={status.color} />
        </Stack>
      </CardContent>
    </Card>
  );
}
