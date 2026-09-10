import { useNavigate, useParams } from 'react-router-dom';
import {
  Card,
  CardContent,
  Divider,
  List,
  ListItem,
  ListItemText,
  Stack,
  Typography,
} from '@mui/material';
import CheckCircleRoundedIcon from '@mui/icons-material/CheckCircleRounded';
import { AppShell } from '@components/AppShell';
import { QueryStateView } from '@components/QueryStateView';
import { formatCurrency } from '@lib/format';
import { useJobCompletionSummary } from './hooks';
import type { JobCompletionSummary } from './api';

/** Format a minutes count as "1h 20m" / "45m". */
function formatDuration(totalMinutes: number): string {
  const minutes = Math.max(0, Math.round(totalMinutes));
  const hours = Math.floor(minutes / 60);
  const mins = minutes % 60;
  if (hours === 0) return `${mins}m`;
  return `${hours}h ${mins}m`;
}

/**
 * Job Completion screen (Requirement 11.6): a summary of the net job duration,
 * recorded parts, and final price. Reached after completing the job on the
 * Active Job screen, or when revisiting a completed job.
 */
export function JobCompletionScreen() {
  const { bookingId = '' } = useParams();
  const navigate = useNavigate();
  const summary = useJobCompletionSummary(bookingId);

  return (
    <AppShell title="Job completed" onBack={() => navigate('/dashboard')}>
      <QueryStateView
        isLoading={summary.isLoading}
        isError={summary.isError}
        error={summary.error}
        onRetry={() => void summary.refetch()}
      >
        {summary.data ? <SummaryContent summary={summary.data} /> : null}
      </QueryStateView>
    </AppShell>
  );
}

function SummaryContent({ summary }: { summary: JobCompletionSummary }) {
  return (
    <Stack spacing={2}>
      <Stack alignItems="center" spacing={1} sx={{ py: 1 }}>
        <CheckCircleRoundedIcon color="success" sx={{ fontSize: 56 }} aria-hidden />
        <Typography variant="h6" fontWeight={700}>
          Job completed
        </Typography>
        <Typography variant="caption" color="text.secondary">
          {summary.reference}
        </Typography>
      </Stack>

      <Card variant="outlined">
        <CardContent>
          <Stack direction="row" justifyContent="space-between">
            <Typography variant="body2" color="text.secondary">
              Net working time
            </Typography>
            <Typography variant="subtitle2" fontWeight={700}>
              {formatDuration(summary.netDurationMinutes)}
            </Typography>
          </Stack>
        </CardContent>
      </Card>

      <Card variant="outlined">
        <CardContent>
          <Typography variant="subtitle2" fontWeight={700} sx={{ mb: 1 }}>
            Parts &amp; materials
          </Typography>
          {summary.parts.length > 0 ? (
            <List dense disablePadding>
              {summary.parts.map((part) => (
                <ListItem
                  key={part.id}
                  disableGutters
                  secondaryAction={
                    <Typography variant="body2" fontWeight={700}>
                      {formatCurrency(part.quantity * part.unitCost, summary.currency)}
                    </Typography>
                  }
                >
                  <ListItemText
                    primary={part.itemName}
                    secondary={`${part.quantity} × ${formatCurrency(part.unitCost, summary.currency)}`}
                  />
                </ListItem>
              ))}
            </List>
          ) : (
            <Typography variant="body2" color="text.secondary">
              No parts recorded.
            </Typography>
          )}
          <Divider sx={{ my: 1 }} />
          <Stack direction="row" justifyContent="space-between">
            <Typography variant="body2" color="text.secondary">
              Parts total
            </Typography>
            <Typography variant="subtitle2" fontWeight={700}>
              {formatCurrency(summary.partsTotal, summary.currency)}
            </Typography>
          </Stack>
        </CardContent>
      </Card>

      <Card variant="outlined" sx={{ bgcolor: 'primary.main', color: 'primary.contrastText' }}>
        <CardContent>
          <Typography variant="overline" sx={{ opacity: 0.9 }}>
            Final price
          </Typography>
          <Typography variant="h4" component="p" fontWeight={700}>
            {formatCurrency(summary.finalPrice, summary.currency)}
          </Typography>
        </CardContent>
      </Card>
    </Stack>
  );
}
