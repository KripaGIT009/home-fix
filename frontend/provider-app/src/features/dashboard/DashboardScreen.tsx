import { useNavigate } from 'react-router-dom';
import { Avatar, Box, Skeleton, Stack, Typography } from '@mui/material';
import PersonRoundedIcon from '@mui/icons-material/PersonRounded';
import { AppShell } from '@components/AppShell';
import { QueryStateView } from '@components/QueryStateView';
import { useAuthStore } from '@stores/authStore';
import { EarningsSummaryCard } from './EarningsSummaryCard';
import { ActiveJobCard } from './ActiveJobCard';
import { useActiveJobs, useEarningsSummary } from './hooks';

/**
 * Provider Dashboard (Requirement 28.8): earnings summary (wallet balance +
 * today's earnings) followed by the active job list with status indicators.
 * Mobile-first single-column layout inside the shared AppShell.
 */
export function DashboardScreen() {
  const navigate = useNavigate();
  const displayName = useAuthStore((state) => state.user?.displayName);

  const summary = useEarningsSummary();
  const activeJobs = useActiveJobs();

  const openJob = (bookingId: string) => navigate(`/jobs/${bookingId}`);

  const initials = (displayName ?? '')
    .split(' ')
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase())
    .join('');

  const jobCount = activeJobs.data?.length ?? 0;

  return (
    <AppShell title="Dashboard" branded>
      <Stack spacing={2}>
        <Stack direction="row" spacing={1.5} alignItems="center">
          <Avatar sx={{ width: 46, height: 46, bgcolor: 'primary.main' }}>
            {initials || <PersonRoundedIcon />}
          </Avatar>
          <Box>
            <Typography variant="body2" color="text.secondary">
              {greeting()}
            </Typography>
            <Typography variant="h6" component="p">
              {displayName ?? 'Welcome back'}
            </Typography>
          </Box>
        </Stack>

        {summary.isLoading ? (
          <Skeleton variant="rounded" height={168} sx={{ borderRadius: 4 }} />
        ) : (
          <QueryStateView
            isLoading={false}
            isError={summary.isError}
            error={summary.error}
            onRetry={() => void summary.refetch()}
          >
            {summary.data ? <EarningsSummaryCard summary={summary.data} /> : null}
          </QueryStateView>
        )}

        <Stack
          direction="row"
          justifyContent="space-between"
          alignItems="baseline"
          sx={{ pt: 0.5 }}
        >
          <Typography variant="subtitle1" fontWeight={700}>
            Active jobs
          </Typography>
          {jobCount > 0 ? (
            <Typography variant="caption" color="text.secondary">
              {jobCount} in progress
            </Typography>
          ) : null}
        </Stack>

        <QueryStateView
          isLoading={activeJobs.isLoading}
          isError={activeJobs.isError}
          error={activeJobs.error}
          onRetry={() => void activeJobs.refetch()}
          isEmpty={jobCount === 0}
          emptyMessage="No active jobs right now. New job offers will appear here."
        >
          <Stack spacing={1.5}>
            {activeJobs.data?.map((job) => (
              <ActiveJobCard key={job.bookingId} job={job} onOpen={openJob} />
            ))}
          </Stack>
        </QueryStateView>
      </Stack>
    </AppShell>
  );
}

/** Time-of-day greeting, so the dashboard opens with something human. */
function greeting(now: Date = new Date()): string {
  const hour = now.getHours();
  if (hour < 12) return 'Good morning';
  if (hour < 17) return 'Good afternoon';
  return 'Good evening';
}
