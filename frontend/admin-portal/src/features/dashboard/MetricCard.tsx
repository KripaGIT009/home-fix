import type { ReactNode } from 'react';
import { Box, Card, CardContent, Stack, Typography } from '@mui/material';
import { brand } from '@lib/theme';

interface MetricCardProps {
  label: string;
  value: string;
  icon: ReactNode;
  /** Optional secondary line, e.g. units or context. */
  hint?: string;
  /** Accent applied to the icon chip, to separate money and risk from volume. */
  tone?: 'neutral' | 'positive' | 'warning';
}

/** Icon-chip colours per tone; the value itself always stays high-contrast ink. */
const TONES = {
  neutral: { bg: brand.accentSoft, fg: brand.accent },
  positive: { bg: brand.greenSoft, fg: brand.green },
  warning: { bg: '#FFF7ED', fg: '#B45309' },
} as const;

/**
 * A single dashboard metric tile. Kept presentational so the DashboardScreen
 * owns the data + formatting and simply passes display-ready strings in.
 *
 * The number is the loudest thing on the tile; the icon is a quiet tint rather
 * than a solid block, so a wall of tiles reads as data instead of as buttons.
 */
export function MetricCard({ label, value, icon, hint, tone = 'neutral' }: MetricCardProps) {
  const palette = TONES[tone];

  return (
    <Card sx={{ height: '100%' }}>
      <CardContent>
        <Stack direction="row" spacing={1.5} alignItems="center" sx={{ mb: 1.5 }}>
          <Box
            aria-hidden
            sx={{
              width: 36,
              height: 36,
              borderRadius: 2,
              display: 'grid',
              placeItems: 'center',
              bgcolor: palette.bg,
              color: palette.fg,
              flexShrink: 0,
            }}
          >
            {icon}
          </Box>
          <Typography variant="body2" color="text.secondary" lineHeight={1.25}>
            {label}
          </Typography>
        </Stack>
        <Typography variant="h4" component="p" fontWeight={800}>
          {value}
        </Typography>
        {hint ? (
          <Typography variant="caption" color="text.secondary">
            {hint}
          </Typography>
        ) : null}
      </CardContent>
    </Card>
  );
}
