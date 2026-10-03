import { useMemo, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Divider,
  Grid,
  IconButton,
  Link,
  Snackbar,
  Stack,
  Tooltip,
  Typography,
  useMediaQuery,
  type Theme,
} from '@mui/material';
import RefreshRoundedIcon from '@mui/icons-material/RefreshRounded';
import ScheduleRoundedIcon from '@mui/icons-material/ScheduleRounded';
import { Link as RouterLink } from 'react-router-dom';
import { ModuleScreen } from '@components/ModuleScreen';
import { QueryStateView } from '@components/QueryStateView';
import { DataTable, type Column } from '@components/DataTable';
import { formatCurrency } from '@lib/format';
import { tenantErrorMessage } from '@features/tenants/model';
import { brand } from '@lib/theme';
import type { TenantBooking } from './api';
import { AssignProviderDialog } from './AssignProviderDialog';
import { useAssignmentQueue, useMyTenant, useNow, useTeam } from './hooks';
import { ServiceAddress } from './ServiceAddress';
import { formatWait, minutesSince, waitColor } from './waiting';

const TIME_FORMAT = new Intl.DateTimeFormat('en-IN', {
  hour: 'numeric',
  minute: '2-digit',
  second: '2-digit',
});

/** "3 Oct, 4:45 pm": queued requests are hours away at most, so no year. */
const SHORT_DATE_TIME = new Intl.DateTimeFormat('en-IN', {
  day: 'numeric',
  month: 'short',
  hour: 'numeric',
  minute: '2-digit',
});

function formatShortDateTime(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : SHORT_DATE_TIME.format(date);
}

/**
 * Requests (Requirements MT-5.1, MT-11.2, MT-11.3): the Tenant's
 * Assignment_Queue — bookings in the agency's area and categories that no
 * provider accepted automatically — oldest first, refreshed every 15 s. Each
 * row shows the service, where the job is (address and a map link), when it is
 * wanted, how long it has waited and the amount; Assign opens the team picker.
 *
 * The team's availability sits beside the queue on wide screens and under it
 * otherwise (Requirement MT-8.4; the assign dialog repeats it), so the
 * admin can see at a glance whether anyone is free before opening a request.
 * The queue is shared with every other agency covering the address, and the
 * first assignment wins; a lost race is explained and the queue refreshed.
 */
export function RequestsScreen() {
  const queueQuery = useAssignmentQueue();
  const tenantQuery = useMyTenant();
  const now = useNow();
  const [assigning, setAssigning] = useState<TenantBooking | null>(null);
  const [lostMessage, setLostMessage] = useState<string | null>(null);
  const [assignedMessage, setAssignedMessage] = useState<string | null>(null);

  const compact = useMediaQuery((theme: Theme) => theme.breakpoints.down('md'));
  const rows = queueQuery.data ?? [];
  const tenantName = tenantQuery.data?.name;

  const columns: Column<TenantBooking>[] = [
    {
      key: 'request',
      header: 'Request',
      render: (row) => (
        <Stack spacing={0.5} sx={{ minWidth: 150 }}>
          <Typography variant="body2" fontWeight={600}>
            {row.serviceName ?? 'Service'}
          </Typography>
          <Stack direction="row" spacing={0.75} alignItems="center">
            <Typography variant="caption" color="text.secondary">
              {row.reference}
            </Typography>
            {row.isEmergency ? (
              <Chip size="small" color="error" label="Emergency" sx={{ height: 20 }} />
            ) : null}
          </Stack>
        </Stack>
      ),
    },
    {
      key: 'where',
      header: 'Where',
      render: (row) => <ServiceAddress booking={row} minWidth={220} />,
    },
    {
      key: 'when',
      header: 'Wanted',
      render: (row) => (
        <Typography variant="body2" noWrap>
          {row.scheduledAt ? formatShortDateTime(row.scheduledAt) : 'As soon as possible'}
        </Typography>
      ),
    },
    {
      key: 'waiting',
      header: 'Waiting',
      render: (row) => <WaitChip booking={row} now={now} />,
    },
    {
      key: 'amount',
      header: 'Amount',
      align: 'right',
      render: (row) => (
        <Typography variant="body2" noWrap>
          {formatCurrency(row.amount, row.currency)}
        </Typography>
      ),
    },
    {
      key: 'action',
      header: '',
      align: 'right',
      render: (row) => (
        <Button size="small" variant="contained" onClick={() => setAssigning(row)}>
          Assign
        </Button>
      ),
    },
  ];

  return (
    <ModuleScreen
      title="Requests"
      description={`Requests in ${tenantName ?? 'your agency'}'s area that no provider accepted automatically. The first partner to assign wins.`}
      actions={
        <Stack direction="row" spacing={1} alignItems="center">
          {queueQuery.dataUpdatedAt ? (
            <Typography variant="caption" color="text.secondary" aria-live="polite">
              Updated {TIME_FORMAT.format(queueQuery.dataUpdatedAt)}
            </Typography>
          ) : null}
          <Tooltip title="Refresh now">
            <span>
              <IconButton
                aria-label="Refresh the queue"
                onClick={() => void queueQuery.refetch()}
                disabled={queueQuery.isFetching}
              >
                <RefreshRoundedIcon />
              </IconButton>
            </span>
          </Tooltip>
        </Stack>
      }
    >
      {lostMessage ? (
        <Alert severity="warning" onClose={() => setLostMessage(null)} sx={{ mb: 2 }}>
          {lostMessage}
        </Alert>
      ) : null}
      {tenantQuery.isError ? (
        <Alert severity="error" sx={{ mb: 2 }}>
          {tenantErrorMessage(tenantQuery.error)}
        </Alert>
      ) : null}

      <Grid container spacing={2}>
        <Grid item xs={12} xl={9}>
          <QueryStateView
            isLoading={queueQuery.isLoading}
            isError={queueQuery.isError}
            error={queueQuery.error}
            onRetry={() => void queueQuery.refetch()}
            isEmpty={rows.length === 0}
            emptyMessage="No requests waiting. New ones appear here automatically."
          >
            {compact ? (
              // On a phone a wide table would push Assign off-screen behind a
              // horizontal scroll; the queue becomes a stack of cards instead.
              <Stack spacing={1.5}>
                {rows.map((row) => (
                  <RequestCard
                    key={row.id}
                    booking={row}
                    now={now}
                    onAssign={() => setAssigning(row)}
                  />
                ))}
              </Stack>
            ) : (
              <DataTable columns={columns} rows={rows} rowKey={(row) => row.id} />
            )}
          </QueryStateView>
        </Grid>
        <Grid item xs={12} xl={3}>
          <TeamNowCard />
        </Grid>
      </Grid>

      {assigning ? (
        <AssignProviderDialog
          booking={assigning}
          onClose={() => setAssigning(null)}
          onAssigned={(name) => {
            setAssignedMessage(
              `${assigning.reference} assigned to ${name}. Waiting for their confirmation.`,
            );
            setAssigning(null);
          }}
          onLost={(message) => {
            setLostMessage(message);
            setAssigning(null);
          }}
        />
      ) : null}

      <Snackbar
        open={assignedMessage !== null}
        autoHideDuration={5000}
        onClose={() => setAssignedMessage(null)}
        message={assignedMessage}
      />
    </ModuleScreen>
  );
}

/** Who in the team is free now, refreshed with the queue (Requirement MT-8.4). */
function TeamNowCard() {
  const teamQuery = useTeam({ poll: true });
  const team = useMemo(
    () =>
      [...(teamQuery.data ?? [])].sort(
        (a, b) =>
          Number(b.assignable && b.availableNow) - Number(a.assignable && a.availableNow) ||
          Number(b.assignable) - Number(a.assignable),
      ),
    [teamQuery.data],
  );
  const availableCount = team.filter((p) => p.assignable && p.availableNow).length;

  return (
    <Card>
      <CardContent>
        <Stack direction="row" justifyContent="space-between" alignItems="baseline">
          <Typography variant="subtitle1">Your team now</Typography>
          <Link component={RouterLink} to="/tenant/team" variant="body2">
            Manage
          </Link>
        </Stack>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
          {teamQuery.data
            ? `${availableCount} of ${team.length} available and assignable`
            : 'Availability and verification of your providers'}
        </Typography>
        <Divider sx={{ mb: 1 }} />
        <QueryStateView
          isLoading={teamQuery.isLoading}
          isError={teamQuery.isError}
          error={teamQuery.error}
          onRetry={() => void teamQuery.refetch()}
          isEmpty={team.length === 0}
          emptyMessage="No providers in your team yet."
        >
          <Box
            sx={{
              display: 'grid',
              // One column in the side panel (xl); spread out when the panel
              // sits full-width under the queue.
              gridTemplateColumns: {
                xs: '1fr',
                sm: 'repeat(2, minmax(0, 1fr))',
                lg: 'repeat(4, minmax(0, 1fr))',
                xl: '1fr',
              },
              columnGap: 3,
              rowGap: 1.25,
            }}
          >
            {team.map((provider) => (
              <Stack
                key={provider.providerId}
                direction="row"
                spacing={1}
                alignItems="center"
                justifyContent="space-between"
              >
                <Box sx={{ minWidth: 0 }}>
                  <Typography variant="body2" fontWeight={600} noWrap>
                    {provider.displayName ?? 'Provider'}
                  </Typography>
                  <Typography variant="caption" color="text.secondary" noWrap component="div">
                    {provider.assignable
                      ? (provider.primarySkill ?? 'Verified')
                      : 'Not assignable yet'}
                  </Typography>
                </Box>
                <AvailabilityDot availableNow={provider.availableNow} />
              </Stack>
            ))}
          </Box>
        </QueryStateView>
      </CardContent>
    </Card>
  );
}

/** A compact availability marker for the narrow team panel. */
function AvailabilityDot({ availableNow }: { availableNow: boolean }) {
  return (
    <Stack direction="row" spacing={0.75} alignItems="center" sx={{ flexShrink: 0 }}>
      <Box
        aria-hidden
        sx={{
          width: 8,
          height: 8,
          borderRadius: '50%',
          bgcolor: availableNow ? brand.green : brand.line,
          border: availableNow ? 'none' : `1px solid ${brand.muted}`,
        }}
      />
      <Typography variant="caption" color={availableNow ? 'success.main' : 'text.secondary'}>
        {availableNow ? 'Available' : 'Unavailable'}
      </Typography>
    </Stack>
  );
}

/** How long the request has waited, coloured as it nears the timeout. */
function WaitChip({ booking, now }: { booking: TenantBooking; now: number }) {
  const minutes = minutesSince(booking.queuedAt ?? booking.createdAt, now);
  const color = waitColor(minutes);
  return (
    <Chip
      size="small"
      icon={<ScheduleRoundedIcon />}
      color={color}
      variant={color === 'default' ? 'outlined' : 'filled'}
      label={formatWait(minutes)}
      aria-label={`Waiting ${formatWait(minutes)}`}
    />
  );
}

/** One queued request as a card, for phone widths. */
function RequestCard({
  booking,
  now,
  onAssign,
}: {
  booking: TenantBooking;
  now: number;
  onAssign: () => void;
}) {
  return (
    <Card>
      <CardContent>
        <Stack direction="row" spacing={1} justifyContent="space-between" alignItems="flex-start">
          <Box sx={{ minWidth: 0 }}>
            <Typography variant="subtitle1">{booking.serviceName ?? 'Service'}</Typography>
            <Stack direction="row" spacing={0.75} alignItems="center">
              <Typography variant="caption" color="text.secondary">
                {booking.reference}
              </Typography>
              {booking.isEmergency ? (
                <Chip size="small" color="error" label="Emergency" sx={{ height: 20 }} />
              ) : null}
            </Stack>
          </Box>
          <WaitChip booking={booking} now={now} />
        </Stack>
        <Box sx={{ my: 1.5 }}>
          <ServiceAddress booking={booking} />
        </Box>
        <Stack direction="row" justifyContent="space-between" sx={{ mb: 1.5 }}>
          <Typography variant="body2" color="text.secondary">
            {booking.scheduledAt
              ? `Wanted ${formatShortDateTime(booking.scheduledAt)}`
              : 'Wanted as soon as possible'}
          </Typography>
          <Typography variant="body2" fontWeight={700}>
            {formatCurrency(booking.amount, booking.currency)}
          </Typography>
        </Stack>
        <Button variant="contained" fullWidth onClick={onAssign}>
          Assign
        </Button>
      </CardContent>
    </Card>
  );
}
