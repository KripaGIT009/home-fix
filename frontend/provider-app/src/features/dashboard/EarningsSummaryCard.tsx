import { Box, Card, CardContent, Divider, Stack, Typography } from '@mui/material';
import AccountBalanceWalletRoundedIcon from '@mui/icons-material/AccountBalanceWalletRounded';
import TodayRoundedIcon from '@mui/icons-material/TodayRounded';
import TaskAltRoundedIcon from '@mui/icons-material/TaskAltRounded';
import { formatCurrency } from '@lib/format';
import type { EarningsSummary } from './api';

interface EarningsSummaryCardProps {
  summary: EarningsSummary;
}

/**
 * Earnings summary at the top of the Dashboard (Requirement 14.1, 28.8):
 * prominent wallet balance with today's net earnings and completed-job count
 * beneath it. Stacks vertically on narrow screens and splits into two columns
 * once there is room.
 */
export function EarningsSummaryCard({ summary }: EarningsSummaryCardProps) {
  const { walletBalance, todayEarnings, todayJobCount, currency } = summary;

  return (
    <Card
      sx={{
        borderColor: 'transparent',
        background: 'linear-gradient(135deg, #14B8A6 0%, #0F766E 100%)',
        color: 'common.white',
      }}
    >
      <CardContent>
        <Stack direction="row" spacing={1} alignItems="center" sx={{ opacity: 0.9 }}>
          <AccountBalanceWalletRoundedIcon fontSize="small" aria-hidden />
          <Typography variant="overline">Wallet balance</Typography>
        </Stack>
        <Typography variant="h3" component="p" fontWeight={800} sx={{ mt: 0.5 }}>
          {formatCurrency(walletBalance, currency)}
        </Typography>

        <Divider sx={{ my: 2, borderColor: 'rgba(255,255,255,0.24)' }} />

        <Stack
          direction="row"
          spacing={2}
          divider={
            <Divider
              orientation="vertical"
              flexItem
              sx={{ borderColor: 'rgba(255,255,255,0.24)' }}
            />
          }
        >
          <Stat
            icon={<TodayRoundedIcon sx={{ fontSize: 16 }} />}
            label="Today's earnings"
            value={formatCurrency(todayEarnings, currency)}
          />
          <Stat
            icon={<TaskAltRoundedIcon sx={{ fontSize: 16 }} />}
            label="Jobs completed today"
            value={String(todayJobCount)}
          />
        </Stack>
      </CardContent>
    </Card>
  );
}

/** One figure in the row beneath the wallet balance. */
function Stat({ icon, label, value }: { icon: React.ReactNode; label: string; value: string }) {
  return (
    <Box sx={{ flex: 1, minWidth: 0 }}>
      <Stack direction="row" spacing={0.5} alignItems="center" sx={{ opacity: 0.85 }}>
        {icon}
        <Typography variant="caption">{label}</Typography>
      </Stack>
      <Typography variant="h6" component="p" fontWeight={800} sx={{ mt: 0.25 }}>
        {value}
      </Typography>
    </Box>
  );
}
