import { Box, Button, Container, Stack, Typography } from '@mui/material';
import ErrorOutlineRoundedIcon from '@mui/icons-material/ErrorOutlineRounded';
import RefreshRoundedIcon from '@mui/icons-material/RefreshRounded';
import type { FallbackProps } from 'react-error-boundary';
import { isApiError } from '@api/client';

/**
 * App-level error boundary fallback. Shows a friendly message and a reset
 * action. Rendered by react-error-boundary when a render/query error escapes.
 */
export function ErrorFallback({ error, resetErrorBoundary }: FallbackProps) {
  const message = isApiError(error)
    ? error.message
    : error instanceof Error
      ? error.message
      : 'Something went wrong.';

  return (
    <Container maxWidth="sm">
      <Stack
        role="alert"
        spacing={2}
        alignItems="center"
        textAlign="center"
        sx={{ minHeight: '100dvh', justifyContent: 'center', py: 4 }}
      >
        <Box
          sx={{
            width: 64,
            height: 64,
            borderRadius: '50%',
            display: 'grid',
            placeItems: 'center',
            bgcolor: 'error.light',
            color: 'error.main',
          }}
        >
          <ErrorOutlineRoundedIcon fontSize="large" aria-hidden />
        </Box>
        <Typography variant="h5" component="h1">
          Something went wrong
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ maxWidth: 360 }}>
          {message}
        </Typography>
        <Button
          variant="contained"
          startIcon={<RefreshRoundedIcon />}
          onClick={resetErrorBoundary}
        >
          Try again
        </Button>
      </Stack>
    </Container>
  );
}
