import type { ReactNode } from 'react';
import { Box, Paper, Stack, Typography } from '@mui/material';
import { brand } from '@lib/theme';
import { BrandLogo } from './BrandLogo';

interface StandalonePageProps {
  /** Page heading, rendered as the h1. */
  title: string;
  /** Short line under the heading. */
  description?: ReactNode;
  /** Rendered at the top right, e.g. a sign-out button. */
  action?: ReactNode;
  /** Card width: `sm` for a short form, `md` for the agency form. */
  width?: 'sm' | 'md';
  children: ReactNode;
}

/**
 * A single card on the tinted canvas, outside the app shell: the pages a person
 * reaches before they hold any console module — registering an agency, its
 * application status, and accepting a staff invitation (email-auth
 * Requirements 5 and 6). The shell would only show them an empty rail.
 */
export function StandalonePage({
  title,
  description,
  action,
  width = 'sm',
  children,
}: StandalonePageProps) {
  return (
    <Box
      sx={{
        minHeight: '100dvh',
        bgcolor: 'background.default',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        px: { xs: 2, sm: 4 },
        py: { xs: 4, md: 6 },
      }}
    >
      <Stack
        direction="row"
        justifyContent="space-between"
        alignItems="center"
        sx={{ width: '100%', maxWidth: width === 'md' ? 760 : 480, mb: 3 }}
      >
        <BrandLogo size={36} />
        {action}
      </Stack>
      <Paper
        elevation={0}
        sx={{
          width: '100%',
          maxWidth: width === 'md' ? 760 : 480,
          p: { xs: 3, sm: 4 },
          border: `1px solid ${brand.line}`,
          bgcolor: 'background.paper',
        }}
      >
        <Stack spacing={1.25} sx={{ mb: 3 }}>
          <Typography variant="h4" component="h1">
            {title}
          </Typography>
          {description ? (
            <Typography variant="body2" color="text.secondary">
              {description}
            </Typography>
          ) : null}
        </Stack>
        {children}
      </Paper>
    </Box>
  );
}
