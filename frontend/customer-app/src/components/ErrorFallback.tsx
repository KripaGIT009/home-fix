import { Box, Button, Stack, Typography } from '@mui/material';
import SentimentDissatisfiedRoundedIcon from '@mui/icons-material/SentimentDissatisfiedRounded';
import RefreshRoundedIcon from '@mui/icons-material/RefreshRounded';
import type { FallbackProps } from 'react-error-boundary';
import { friendlyErrorMessage } from '@api/client';
import { brand, radius } from '@lib/theme';
import { BrandLogo } from './BrandLogo';

/**
 * App-level error boundary fallback. A calm full-page message with a reset
 * action; a render bug's developer-facing text is never shown to customers.
 */
export function ErrorFallback({ error, resetErrorBoundary }: FallbackProps) {
  return (
    <Box
      sx={{
        minHeight: '100dvh',
        display: 'grid',
        placeItems: 'center',
        px: 2,
        bgcolor: 'background.default',
      }}
    >
      <Stack
        role="alert"
        spacing={2}
        alignItems="center"
        textAlign="center"
        sx={{
          maxWidth: 440,
          width: '100%',
          p: { xs: 3, md: 5 },
          borderRadius: `${radius.xl}px`,
          bgcolor: 'background.paper',
          border: `1px solid ${brand.line}`,
        }}
      >
        <BrandLogo size={32} />
        <Box
          aria-hidden
          sx={{
            width: 64,
            height: 64,
            borderRadius: `${radius.lg}px`,
            display: 'grid',
            placeItems: 'center',
            bgcolor: brand.warmSoft,
            color: brand.warmDark,
            mt: 1,
          }}
        >
          <SentimentDissatisfiedRoundedIcon sx={{ fontSize: 32 }} />
        </Box>
        <Typography variant="h4" component="h1">
          Something went wrong
        </Typography>
        <Typography variant="body1" color="text.secondary">
          {friendlyErrorMessage(
            error,
            'This screen ran into a problem. Trying again usually fixes it.',
          )}
        </Typography>
        <Button
          variant="contained"
          size="large"
          startIcon={<RefreshRoundedIcon />}
          onClick={resetErrorBoundary}
        >
          Try again
        </Button>
      </Stack>
    </Box>
  );
}
