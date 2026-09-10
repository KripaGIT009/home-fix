import { Box, Stack, Typography } from '@mui/material';
import type { SxProps, Theme } from '@mui/material/styles';

interface BrandLogoProps {
  /** Overall mark size in pixels; the wordmark scales with it. */
  size?: number;
  /** Render for a dark/branded background (splash, hero) instead of white. */
  inverted?: boolean;
  /** Hide the "HomeFix" wordmark and show the house mark alone. */
  markOnly?: boolean;
  sx?: SxProps<Theme>;
}

/**
 * The HomeFix mark: a house whose roof carries a wrench, over the two-tone
 * "Home / Fix" wordmark. Used on the splash, the login header and the app bar
 * so the brand reads the same everywhere.
 */
export function BrandLogo({ size = 40, inverted = false, markOnly = false, sx }: BrandLogoProps) {
  const wordmarkSize = size * 0.6;

  return (
    <Stack direction="row" spacing={1.25} alignItems="center" {...(sx ? { sx } : {})}>
      <Box
        aria-hidden
        sx={{
          width: size,
          height: size,
          borderRadius: `${size * 0.28}px`,
          display: 'grid',
          placeItems: 'center',
          flexShrink: 0,
          background: inverted
            ? 'rgba(255, 255, 255, 0.16)'
            : 'linear-gradient(140deg, #3B82F6 0%, #1D4ED8 100%)',
          boxShadow: inverted ? 'none' : '0 6px 16px -8px rgba(29, 78, 216, 0.9)',
        }}
      >
        <svg
          width={size * 0.58}
          height={size * 0.58}
          viewBox="0 0 24 24"
          fill="none"
          focusable="false"
        >
          <path
            d="M3.4 10.6 12 3.5l8.6 7.1"
            stroke="#FFFFFF"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
          <path
            d="M5.5 12.2V19a1.3 1.3 0 0 0 1.3 1.3h10.4A1.3 1.3 0 0 0 18.5 19v-6.8"
            stroke="#FFFFFF"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
          <path
            d="M13.9 12.6a2.2 2.2 0 0 0-3 2.9l-1.9 1.9 1.4 1.4 1.9-1.9a2.2 2.2 0 0 0 2.9-3l-1.1 1.1-1.2-1.2 1-1.2Z"
            fill="#FFFFFF"
          />
        </svg>
      </Box>

      {markOnly ? null : (
        <Typography
          component="span"
          sx={{
            fontSize: wordmarkSize,
            fontWeight: 800,
            letterSpacing: '-0.03em',
            lineHeight: 1,
            color: inverted ? '#FFFFFF' : 'text.primary',
          }}
        >
          Home
          <Box
            component="span"
            sx={{ color: inverted ? 'rgba(255,255,255,0.75)' : 'primary.main' }}
          >
            Fix
          </Box>
        </Typography>
      )}
    </Stack>
  );
}
