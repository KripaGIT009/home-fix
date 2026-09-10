import type { ReactNode } from 'react';
import { Alert, Box, Button, CircularProgress, Stack, Typography } from '@mui/material';
import InboxRoundedIcon from '@mui/icons-material/InboxRounded';
import RefreshRoundedIcon from '@mui/icons-material/RefreshRounded';
import { isApiError } from '@api/client';

interface QueryStateViewProps {
  isLoading: boolean;
  isError: boolean;
  error?: unknown;
  onRetry?: () => void;
  /** Shown when not loading, not error, and there is no data. */
  isEmpty?: boolean;
  emptyMessage?: string;
  children: ReactNode;
}

/**
 * Small helper that renders the standard loading / error / empty states around
 * query-backed content, so screens don't repeat the boilerplate.
 */
export function QueryStateView({
  isLoading,
  isError,
  error,
  onRetry,
  isEmpty,
  emptyMessage = 'Nothing to show yet.',
  children,
}: QueryStateViewProps) {
  if (isLoading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', py: 8 }}>
        <CircularProgress aria-label="Loading" />
      </Box>
    );
  }

  if (isError) {
    const message = isApiError(error) ? error.message : 'Something went wrong. Please try again.';
    return (
      <Stack spacing={2} sx={{ py: 3 }} alignItems="flex-start">
        <Alert severity="error" sx={{ width: '100%' }}>
          {message}
        </Alert>
        {onRetry ? (
          <Button variant="outlined" startIcon={<RefreshRoundedIcon />} onClick={onRetry}>
            Try again
          </Button>
        ) : null}
      </Stack>
    );
  }

  if (isEmpty) {
    return (
      <Stack spacing={1} alignItems="center" sx={{ py: 7, textAlign: 'center' }}>
        <Box
          sx={{
            width: 56,
            height: 56,
            borderRadius: '50%',
            display: 'grid',
            placeItems: 'center',
            bgcolor: 'action.hover',
            color: 'text.secondary',
          }}
        >
          <InboxRoundedIcon aria-hidden />
        </Box>
        <Typography variant="body2" color="text.secondary" sx={{ maxWidth: 280 }}>
          {emptyMessage}
        </Typography>
      </Stack>
    );
  }

  return <>{children}</>;
}
