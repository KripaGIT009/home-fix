import { Grid, Stack, Typography } from '@mui/material';
import EventNoteRoundedIcon from '@mui/icons-material/EventNoteRounded';
import EngineeringRoundedIcon from '@mui/icons-material/EngineeringRounded';
import PersonAddRoundedIcon from '@mui/icons-material/PersonAddRounded';
import PaymentsRoundedIcon from '@mui/icons-material/PaymentsRounded';
import TimerRoundedIcon from '@mui/icons-material/TimerRounded';
import ReportProblemRoundedIcon from '@mui/icons-material/ReportProblemRounded';
import StarRoundedIcon from '@mui/icons-material/StarRounded';
import { AppShell } from '@components/AppShell';
import { QueryStateView } from '@components/QueryStateView';
import { formatCurrency, formatNumber } from '@lib/format';
import { MetricCard } from './MetricCard';
import { DASHBOARD_REFRESH_MS, useDashboardMetrics } from './hooks';
import type { DashboardMetrics } from './api';

/** Format a response time given in seconds as a compact human string. */
function formatResponseTime(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return '—';
  if (seconds < 60) return `${Math.round(seconds)}s`;
  const minutes = Math.floor(seconds / 60);
  const rem = Math.round(seconds % 60);
  return rem === 0 ? `${minutes}m` : `${minutes}m ${rem}s`;
}

/**
 * Admin dashboard (Requirement 19.1): the seven live platform metrics laid out
 * as cards on a responsive grid. The underlying query polls every 60 seconds
 * (see useDashboardMetrics), so the tiles refresh automatically without any
 * manual reload.
 */
export function DashboardScreen() {
  const metricsQuery = useDashboardMetrics();
  const data = metricsQuery.data;

  return (
    <AppShell title="Dashboard">
      <Stack
        direction="row"
        justifyContent="space-between"
        alignItems="center"
        spacing={2}
        sx={{ mb: 3 }}
      >
        <Typography variant="h5" component="h2" fontWeight={700}>
          Platform overview
        </Typography>
        <Typography variant="caption" color="text.secondary" aria-live="polite">
          Auto-refreshes every {Math.round(DASHBOARD_REFRESH_MS / 1000)}s
        </Typography>
      </Stack>

      <QueryStateView
        isLoading={metricsQuery.isLoading}
        isError={metricsQuery.isError}
        error={metricsQuery.error}
        onRetry={() => void metricsQuery.refetch()}
      >
        {data ? <MetricsGrid metrics={data} /> : null}
      </QueryStateView>
    </AppShell>
  );
}

function MetricsGrid({ metrics }: { metrics: DashboardMetrics }) {
  const currency = metrics.currency || 'INR';
  const tiles = [
    {
      label: 'Active bookings',
      value: formatNumber(metrics.activeBookings),
      icon: <EventNoteRoundedIcon fontSize="small" />,
    },
    {
      label: 'Providers online',
      value: formatNumber(metrics.activeProvidersOnline),
      icon: <EngineeringRoundedIcon fontSize="small" />,
    },
    {
      label: 'New registrations (24h)',
      value: formatNumber(metrics.newRegistrations24h),
      icon: <PersonAddRoundedIcon fontSize="small" />,
    },
    {
      label: 'Gross revenue (24h)',
      value: formatCurrency(metrics.grossRevenue24h, currency),
      icon: <PaymentsRoundedIcon fontSize="small" />,
      tone: 'positive' as const,
    },
    {
      label: 'Avg. provider response (24h)',
      value: formatResponseTime(metrics.avgProviderResponseTimeSeconds),
      icon: <TimerRoundedIcon fontSize="small" />,
    },
    {
      label: 'Open complaints',
      value: formatNumber(metrics.openComplaints),
      icon: <ReportProblemRoundedIcon fontSize="small" />,
      tone: 'warning' as const,
    },
    {
      label: 'Platform rating',
      value: metrics.platformRating.toFixed(1),
      hint: 'out of 5.0',
      icon: <StarRoundedIcon fontSize="small" />,
      tone: 'positive' as const,
    },
  ] as const;

  return (
    <Grid container spacing={2}>
      {tiles.map((tile) => (
        <Grid key={tile.label} item xs={12} sm={6} md={4} lg={3}>
          <MetricCard
            label={tile.label}
            value={tile.value}
            icon={tile.icon}
            {...('hint' in tile && tile.hint ? { hint: tile.hint } : {})}
            {...('tone' in tile && tile.tone ? { tone: tile.tone } : {})}
          />
        </Grid>
      ))}
    </Grid>
  );
}
