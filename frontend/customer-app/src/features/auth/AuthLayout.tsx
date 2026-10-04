import type { ReactNode } from 'react';
import { Box, Link, Stack, Typography } from '@mui/material';
import VerifiedUserRoundedIcon from '@mui/icons-material/VerifiedUserRounded';
import ReceiptLongRoundedIcon from '@mui/icons-material/ReceiptLongRounded';
import NearMeRoundedIcon from '@mui/icons-material/NearMeRounded';
import { BrandLogo } from '@components/BrandLogo';
import { brand, brandGradient, radius, shadows } from '@lib/theme';

/** Proof points shown on the brand panel. */
const VALUE_PROPS = [
  {
    icon: <VerifiedUserRoundedIcon />,
    label: 'Verified professionals',
    caption: 'Document and background checks before any job.',
  },
  {
    icon: <ReceiptLongRoundedIcon />,
    label: 'Upfront pricing',
    caption: 'A full itemised estimate before you confirm.',
  },
  {
    icon: <NearMeRoundedIcon />,
    label: 'Live tracking',
    caption: 'Follow your pro to the door, around the clock.',
  },
] as const;

/**
 * The frame every public auth screen shares: login, sign-up, the email code
 * and password reset.
 *
 * Desktop is a split layout — brand panel and form card — and mobile a single
 * column under a compact brand band. The screen supplies the card's contents.
 */
export function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <Box
      sx={{
        minHeight: '100dvh',
        display: 'grid',
        gridTemplateColumns: { xs: '1fr', md: 'minmax(0, 1fr) minmax(0, 1fr)' },
        bgcolor: 'background.default',
      }}
    >
      <BrandPanel />

      <Box
        component="main"
        sx={{
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: { xs: 'flex-start', md: 'center' },
          px: { xs: 2, sm: 3 },
          pb: { xs: 4, md: 6 },
          pt: { md: 6 },
          mt: { xs: -5, md: 0 },
          position: 'relative',
        }}
      >
        <Box
          sx={{
            width: '100%',
            maxWidth: 440,
            p: { xs: 3, sm: 4 },
            bgcolor: 'background.paper',
            borderRadius: `${radius.xl}px`,
            border: `1px solid ${brand.line}`,
            boxShadow: { xs: shadows.raised, md: shadows.card },
          }}
        >
          {children}
        </Box>

        <Typography
          variant="caption"
          color="text.secondary"
          align="center"
          sx={{ mt: 3, display: 'block', maxWidth: 360 }}
        >
          By continuing, you agree to our{' '}
          <Link href="#" underline="hover" fontWeight={600}>
            Terms
          </Link>{' '}
          and{' '}
          <Link href="#" underline="hover" fontWeight={600}>
            Privacy Policy
          </Link>
          .
        </Typography>
      </Box>
    </Box>
  );
}

/** The brand side of the split: full-height panel on desktop, a band on mobile. */
function BrandPanel() {
  return (
    <Box
      component="aside"
      aria-label="About HomeFix"
      sx={{
        position: 'relative',
        overflow: 'hidden',
        color: 'common.white',
        background: brandGradient,
        px: { xs: 3, md: 7, lg: 10 },
        pt: { xs: 3, md: 6 },
        pb: { xs: 9, md: 6 },
        display: 'flex',
        flexDirection: 'column',
      }}
    >
      <PatternDecoration />
      <Box sx={{ position: 'relative' }}>
        <BrandLogo size={40} inverted />
      </Box>

      <Box
        sx={{
          position: 'relative',
          flexGrow: 1,
          display: 'flex',
          flexDirection: 'column',
          justifyContent: 'center',
          maxWidth: 520,
          mt: { xs: 3, md: 0 },
        }}
      >
        <Typography
          component="p"
          sx={{
            fontSize: { xs: '1.625rem', md: '2.75rem' },
            fontWeight: 800,
            letterSpacing: '-0.03em',
            lineHeight: 1.12,
          }}
        >
          Verified help for every home.
        </Typography>
        <Typography
          sx={{
            opacity: 0.85,
            mt: { xs: 1, md: 2 },
            fontSize: { xs: '0.9375rem', md: '1.125rem' },
          }}
        >
          Book trusted pros for repairs, cleaning and more — with the price upfront and live
          tracking to your door.
        </Typography>

        <Stack spacing={2.5} sx={{ display: { xs: 'none', md: 'flex' }, mt: 6 }}>
          {VALUE_PROPS.map((prop) => (
            <Stack key={prop.label} direction="row" spacing={2} alignItems="center">
              <Box
                aria-hidden
                sx={{
                  width: 44,
                  height: 44,
                  borderRadius: `${radius.md}px`,
                  display: 'grid',
                  placeItems: 'center',
                  bgcolor: 'rgba(255,255,255,0.12)',
                  border: '1px solid rgba(255,255,255,0.16)',
                  color: brand.warm,
                  flexShrink: 0,
                }}
              >
                {prop.icon}
              </Box>
              <Box>
                <Typography variant="subtitle1" fontWeight={700}>
                  {prop.label}
                </Typography>
                <Typography variant="body2" sx={{ opacity: 0.78 }}>
                  {prop.caption}
                </Typography>
              </Box>
            </Stack>
          ))}
        </Stack>
      </Box>

      <Typography
        variant="caption"
        sx={{ position: 'relative', opacity: 0.6, display: { xs: 'none', md: 'block' } }}
      >
        © {new Date().getFullYear()} HomeFix · Verified Help. Anytime. Anywhere.
      </Typography>
    </Box>
  );
}

/** Soft concentric rings and a house outline, drawn in SVG — no images. */
function PatternDecoration() {
  return (
    <Box
      component="svg"
      aria-hidden
      viewBox="0 0 600 600"
      sx={{
        position: 'absolute',
        right: { xs: -180, md: -140 },
        bottom: { xs: -260, md: -120 },
        width: { xs: 420, md: 620 },
        height: 'auto',
        opacity: 0.5,
        pointerEvents: 'none',
      }}
    >
      {[120, 190, 260].map((r) => (
        <circle
          key={r}
          cx="300"
          cy="300"
          r={r}
          fill="none"
          stroke="rgba(255,255,255,0.16)"
          strokeWidth="1.5"
        />
      ))}
      <path
        d="M220 320 300 250l80 70M238 306v84h124v-84"
        fill="none"
        stroke="rgba(255,255,255,0.35)"
        strokeWidth="8"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </Box>
  );
}
