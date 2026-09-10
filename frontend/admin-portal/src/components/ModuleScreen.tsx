import type { ReactNode } from 'react';
import { Box, Stack, Typography } from '@mui/material';
import { AppShell } from './AppShell';

interface ModuleScreenProps {
  /** Module title shown in the app bar and as the page heading. */
  title: string;
  /** Optional short description under the heading. */
  description?: string;
  /** Optional actions (buttons, filters) rendered on the right of the header. */
  actions?: ReactNode;
  children: ReactNode;
}

/**
 * Standard wrapper for an operational module: renders the desktop AppShell and
 * a consistent page header (heading + description + optional actions) above the
 * module content.
 */
export function ModuleScreen({ title, description, actions, children }: ModuleScreenProps) {
  return (
    <AppShell title={title}>
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        justifyContent="space-between"
        alignItems={{ xs: 'flex-start', sm: 'center' }}
        spacing={2}
        sx={{ mb: 3 }}
      >
        <Box>
          <Typography variant="h5" component="h2" fontWeight={700}>
            {title}
          </Typography>
          {description ? (
            <Typography variant="body2" color="text.secondary">
              {description}
            </Typography>
          ) : null}
        </Box>
        {actions ? <Box>{actions}</Box> : null}
      </Stack>
      {children}
    </AppShell>
  );
}
