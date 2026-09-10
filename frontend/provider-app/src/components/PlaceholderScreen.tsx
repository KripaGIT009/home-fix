import { Box, Container, Stack, Typography } from '@mui/material';
import ConstructionRoundedIcon from '@mui/icons-material/ConstructionRounded';

interface PlaceholderScreenProps {
  title: string;
  description?: string;
}

/**
 * Fallback screen for routes with no destination — chiefly the wildcard
 * "not found" route. Kept deliberately plain so a wrong URL reads as a dead end
 * rather than as a broken screen.
 */
export function PlaceholderScreen({ title, description }: PlaceholderScreenProps) {
  return (
    <Container maxWidth="sm">
      <Stack
        spacing={1.5}
        alignItems="center"
        textAlign="center"
        sx={{ minHeight: '60dvh', justifyContent: 'center', py: 6 }}
      >
        <Box
          sx={{
            width: 60,
            height: 60,
            borderRadius: '50%',
            display: 'grid',
            placeItems: 'center',
            bgcolor: 'action.hover',
            color: 'text.secondary',
          }}
        >
          <ConstructionRoundedIcon aria-hidden />
        </Box>
        <Typography variant="h5" component="h1">
          {title}
        </Typography>
        {description ? (
          <Typography variant="body2" color="text.secondary" sx={{ maxWidth: 320 }}>
            {description}
          </Typography>
        ) : null}
      </Stack>
    </Container>
  );
}
