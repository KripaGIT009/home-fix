import type { ReactNode } from 'react';
import { Box, Stack, Typography } from '@mui/material';

/**
 * The storefront's section header: a bold title, an optional one-line
 * caption, and an optional action (usually a "See all" link) on the right.
 */
export function SectionHeading({
  id,
  title,
  caption,
  action,
}: {
  /** Id for the heading, so the section can be `aria-labelledby` it. */
  id?: string;
  title: ReactNode;
  caption?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <Stack
      direction="row"
      alignItems="flex-end"
      justifyContent="space-between"
      spacing={2}
      sx={{ mb: { xs: 1.75, md: 2.25 } }}
    >
      <Box sx={{ minWidth: 0 }}>
        <Typography
          id={id}
          variant="h2"
          sx={{
            fontSize: { xs: '1.1875rem', md: '1.375rem' },
            fontWeight: 700,
            letterSpacing: '-0.02em',
            lineHeight: 1.3,
          }}
        >
          {title}
        </Typography>
        {caption ? (
          <Typography
            variant="body2"
            color="text.secondary"
            sx={{ mt: 0.25, fontSize: '0.8125rem' }}
          >
            {caption}
          </Typography>
        ) : null}
      </Box>
      {action ? <Box sx={{ flexShrink: 0 }}>{action}</Box> : null}
    </Stack>
  );
}
