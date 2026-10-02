import type { ReactNode } from 'react';
import { Box, Button, Stack, Typography } from '@mui/material';
import CloudOffRoundedIcon from '@mui/icons-material/CloudOffRounded';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import RefreshRoundedIcon from '@mui/icons-material/RefreshRounded';
import { friendlyErrorMessage, isUnreachableError } from '@api/client';
import { brand, radius } from '@lib/theme';

/**
 * A calm inline notice for a failed load: a soft neutral panel with a plain
 * explanation and a retry, rather than an alarming red slab. Offline/outage
 * errors get a "can't reach us" treatment; anything else a generic one.
 */
export function InlineError({
  error,
  title,
  onRetry,
  retryLabel = 'Try again',
}: {
  error: unknown;
  title?: string;
  onRetry?: () => void;
  retryLabel?: string;
}) {
  const unreachable = isUnreachableError(error);
  const heading = title ?? (unreachable ? 'Connection problem' : "Couldn't load this");
  return (
    <Stack
      role="alert"
      direction={{ xs: 'column', sm: 'row' }}
      spacing={2}
      alignItems={{ xs: 'flex-start', sm: 'center' }}
      sx={{
        p: { xs: 2, md: 2.5 },
        borderRadius: `${radius.lg}px`,
        bgcolor: 'background.paper',
        border: `1px solid ${brand.line}`,
      }}
    >
      <Box
        aria-hidden
        sx={{
          width: 44,
          height: 44,
          borderRadius: `${radius.md}px`,
          display: 'grid',
          placeItems: 'center',
          flexShrink: 0,
          bgcolor: brand.warmSoft,
          color: brand.warmDark,
        }}
      >
        {unreachable ? <CloudOffRoundedIcon /> : <InfoOutlinedIcon />}
      </Box>
      <Box sx={{ flexGrow: 1, minWidth: 0 }}>
        <Typography variant="subtitle1" fontWeight={700}>
          {heading}
        </Typography>
        <Typography variant="body2" color="text.secondary">
          {friendlyErrorMessage(error)}
        </Typography>
      </Box>
      {onRetry ? (
        <Button
          variant="outlined"
          startIcon={<RefreshRoundedIcon />}
          onClick={onRetry}
          sx={{ flexShrink: 0 }}
        >
          {retryLabel}
        </Button>
      ) : null}
    </Stack>
  );
}

/** A centred empty state: icon, headline, supporting copy and an optional action. */
export function EmptyState({
  icon,
  title,
  description,
  action,
  compact = false,
}: {
  icon: ReactNode;
  title: string;
  description?: ReactNode;
  action?: ReactNode;
  compact?: boolean;
}) {
  return (
    <Stack
      spacing={1.5}
      alignItems="center"
      textAlign="center"
      sx={{ py: compact ? 4 : { xs: 6, md: 9 }, px: 2 }}
    >
      <Box
        aria-hidden
        sx={{
          width: 64,
          height: 64,
          borderRadius: `${radius.lg}px`,
          display: 'grid',
          placeItems: 'center',
          bgcolor: brand.accentSoft,
          color: 'primary.main',
          '& svg': { fontSize: 30 },
        }}
      >
        {icon}
      </Box>
      <Typography variant="h5" component="h2">
        {title}
      </Typography>
      {description ? (
        <Typography variant="body2" color="text.secondary" sx={{ maxWidth: 380 }}>
          {description}
        </Typography>
      ) : null}
      {action ? <Box sx={{ pt: 1 }}>{action}</Box> : null}
    </Stack>
  );
}

/** A tinted rounded-square icon holder — the app's single icon treatment. */
export function IconTile({
  children,
  size = 44,
  bg = brand.accentSoft,
  color = brand.accent,
}: {
  children: ReactNode;
  size?: number;
  bg?: string;
  color?: string;
}) {
  return (
    <Box
      aria-hidden
      sx={{
        width: size,
        height: size,
        borderRadius: `${Math.round(size * 0.3)}px`,
        display: 'grid',
        placeItems: 'center',
        flexShrink: 0,
        bgcolor: bg,
        color,
        '& svg': { fontSize: Math.round(size * 0.52) },
      }}
    >
      {children}
    </Box>
  );
}
