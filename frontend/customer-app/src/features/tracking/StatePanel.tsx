import type { ReactNode } from 'react';
import { Box, Card, CardContent, Stack, Typography } from '@mui/material';
import { IconTile } from '@components/StateViews';
import { brand } from '@lib/theme';

export type StatePanelTone = 'primary' | 'warm' | 'success' | 'neutral' | 'danger';

/**
 * Shared frame for every non-map booking state: icon, headline, explanation,
 * actions. Used by live tracking and by panels that also appear on the booking
 * detail screen (paying for a completed job), so a state looks the same
 * wherever it is shown.
 */
export function StatePanel({
  icon,
  tone = 'primary',
  title,
  children,
  extra,
  actions,
  visual,
}: {
  icon: ReactNode;
  tone?: StatePanelTone;
  title: string;
  children: ReactNode;
  /**
   * Interactive content (e.g. a choice of payment method) shown under the
   * explanation, outside its live region so screen readers do not re-announce
   * form controls on every update.
   */
  extra?: ReactNode;
  actions?: ReactNode;
  /** Replaces the icon tile with a custom visual (e.g. the searching radar). */
  visual?: ReactNode;
}) {
  const colors = {
    primary: { bg: brand.accentSoft, fg: brand.accent },
    warm: { bg: brand.warmSoft, fg: brand.warmDark },
    success: { bg: brand.greenSoft, fg: brand.green },
    neutral: { bg: brand.slateSoft, fg: '#475467' },
    danger: { bg: brand.redSoft, fg: brand.red },
  }[tone];
  return (
    <Card sx={{ overflow: 'hidden' }}>
      <CardContent sx={{ py: { xs: 3, md: 5 }, px: { xs: 2.5, md: 5 } }}>
        <Stack
          spacing={2}
          alignItems={{ xs: 'center', sm: 'flex-start' }}
          textAlign={{ xs: 'center', sm: 'left' }}
        >
          {visual ?? (
            <IconTile size={60} bg={colors.bg} color={colors.fg}>
              {icon}
            </IconTile>
          )}
          <Box role="status" aria-live="polite">
            <Typography variant="h3" component="p">
              {title}
            </Typography>
            <Typography
              variant="body1"
              color="text.secondary"
              component="div"
              sx={{ mt: 1, maxWidth: 560 }}
            >
              {children}
            </Typography>
          </Box>
          {extra ? <Box sx={{ width: '100%', maxWidth: 560 }}>{extra}</Box> : null}
          {actions ? (
            <Stack
              direction={{ xs: 'column', sm: 'row' }}
              spacing={1.25}
              sx={{ pt: 1, width: { xs: '100%', sm: 'auto' } }}
            >
              {actions}
            </Stack>
          ) : null}
        </Stack>
      </CardContent>
    </Card>
  );
}
