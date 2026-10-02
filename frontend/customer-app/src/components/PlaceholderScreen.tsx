import { Link as RouterLink } from 'react-router-dom';
import { Box, Button, Stack, Typography } from '@mui/material';
import ExploreOffRoundedIcon from '@mui/icons-material/ExploreOffRounded';
import { brand, radius } from '@lib/theme';
import { BrandLogo } from './BrandLogo';

interface PlaceholderScreenProps {
  title: string;
  description?: string;
}

/**
 * Fallback screen for routes with no destination — chiefly the wildcard
 * "not found" route. A clear dead end with one way out.
 */
export function PlaceholderScreen({ title, description }: PlaceholderScreenProps) {
  return (
    <Box
      sx={{
        minHeight: '100dvh',
        display: 'flex',
        flexDirection: 'column',
        bgcolor: 'background.default',
      }}
    >
      <Box sx={{ px: { xs: 2, md: 4 }, py: 2.5 }}>
        <BrandLogo size={32} />
      </Box>
      <Stack
        component="main"
        spacing={2}
        alignItems="center"
        textAlign="center"
        sx={{ flexGrow: 1, justifyContent: 'center', px: 2, pb: 10 }}
      >
        <Box
          aria-hidden
          sx={{
            width: 72,
            height: 72,
            borderRadius: `${radius.xl}px`,
            display: 'grid',
            placeItems: 'center',
            bgcolor: brand.accentSoft,
            color: 'primary.main',
          }}
        >
          <ExploreOffRoundedIcon sx={{ fontSize: 34 }} />
        </Box>
        <Typography variant="h2" component="h1">
          {title}
        </Typography>
        {description ? (
          <Typography variant="body1" color="text.secondary" sx={{ maxWidth: 400 }}>
            {description}
          </Typography>
        ) : null}
        <Button component={RouterLink} to="/home" variant="contained" size="large">
          Go to home
        </Button>
      </Stack>
    </Box>
  );
}
