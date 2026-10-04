import type { ReactNode } from 'react';
import { Box, Card, CardContent, Container, Link, Stack, Typography } from '@mui/material';
import { BrandLogo } from '@components/BrandLogo';

interface AuthLayoutProps {
  title: string;
  subtitle: ReactNode;
  /** The card's content: the current step's form. */
  children: ReactNode;
  /** Shown under the card, e.g. the "Create account" prompt. */
  below?: ReactNode;
}

/**
 * The branded frame of the signed-out screens (sign-in, sign-up, emailed
 * code, password reset): a teal band with the logo and a heading, overlapped
 * by a card holding the form, and the terms line at the foot.
 */
export function AuthLayout({ title, subtitle, children, below }: AuthLayoutProps) {
  return (
    <Box
      sx={{
        minHeight: '100dvh',
        display: 'flex',
        flexDirection: 'column',
        // Branded band behind the header that the login card overlaps.
        background: 'linear-gradient(180deg, #14B8A6 0%, #0F766E 42%, #F5F7FB 42%)',
      }}
    >
      <Container
        maxWidth="sm"
        sx={{ py: 4, display: 'flex', flexDirection: 'column', flexGrow: 1 }}
      >
        <Stack spacing={1.5} sx={{ color: 'common.white', mb: 3 }}>
          <BrandLogo size={44} inverted showRole />
          <Box>
            <Typography variant="h4" component="h1">
              {title}
            </Typography>
            <Typography variant="body2" sx={{ opacity: 0.85, mt: 0.5 }}>
              {subtitle}
            </Typography>
          </Box>
        </Stack>

        <Card>
          <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>{children}</CardContent>
        </Card>

        {below ? <Box sx={{ mt: 2.5 }}>{below}</Box> : null}

        <Box sx={{ flexGrow: 1 }} />

        <Typography variant="caption" color="text.secondary" align="center" sx={{ mt: 3 }}>
          By continuing, you agree to our{' '}
          <Link href="#" underline="hover" fontWeight={600}>
            Terms
          </Link>{' '}
          &amp;{' '}
          <Link href="#" underline="hover" fontWeight={600}>
            Privacy Policy
          </Link>
          .
        </Typography>
      </Container>
    </Box>
  );
}
