import { Link as RouterLink } from 'react-router-dom';
import { Box, Stack, Typography } from '@mui/material';
import ScheduleRoundedIcon from '@mui/icons-material/ScheduleRounded';
import { formatDuration, formatPrice } from '@lib/format';
import { brand, radius } from '@lib/theme';
import { ServiceArt } from './ServiceArt';
import type { ServiceCategory, ServiceSubcategory } from './api';

/**
 * A storefront service card: a square illustrated thumbnail, the name, the
 * typical visit length and the "from" price. The whole card is one link to the
 * booking flow. Width comes from the parent (a rail item or a grid cell).
 */
export function ServiceTile({
  category,
  subcategory,
}: {
  category: ServiceCategory | undefined;
  subcategory: ServiceSubcategory;
}) {
  return (
    <Box
      component={RouterLink}
      to={`/book/${subcategory.id}`}
      aria-label={`${subcategory.name}, ${formatDuration(subcategory.estimatedDurationMin)}, from ${formatPrice(subcategory.basePrice)}${subcategory.emergencyAvailable ? ', available 24×7' : ''}`}
      sx={{
        display: 'block',
        color: 'inherit',
        textDecoration: 'none',
        borderRadius: `${radius.md}px`,
        '&:focus-visible': { outline: `2px solid ${brand.accent}`, outlineOffset: 3 },
        '&:hover .service-tile-thumb > *': { transform: 'scale(1.03)' },
        '&:hover .service-tile-name': { color: 'primary.main' },
      }}
    >
      <Box
        className="service-tile-thumb"
        sx={{ borderRadius: `${radius.md}px`, overflow: 'hidden' }}
      >
        <ServiceArt
          category={category}
          subcategory={subcategory}
          sx={{ transition: 'transform .25s ease' }}
        />
      </Box>
      <Typography
        className="service-tile-name"
        variant="body2"
        fontWeight={600}
        sx={{
          mt: 1.25,
          lineHeight: 1.4,
          color: 'text.primary',
          transition: 'color .2s',
          display: '-webkit-box',
          WebkitLineClamp: 2,
          WebkitBoxOrient: 'vertical',
          overflow: 'hidden',
        }}
      >
        {subcategory.name}
      </Typography>
      <Stack
        direction="row"
        spacing={0.5}
        alignItems="center"
        sx={{ mt: 0.5, color: 'text.secondary' }}
      >
        <ScheduleRoundedIcon sx={{ fontSize: 14 }} aria-hidden />
        <Typography variant="caption" sx={{ fontSize: '0.75rem' }}>
          {formatDuration(subcategory.estimatedDurationMin)}
        </Typography>
      </Stack>
      <Typography variant="body2" sx={{ mt: 0.25 }}>
        <Box component="span" sx={{ color: 'text.secondary' }}>
          from{' '}
        </Box>
        <Box component="span" sx={{ fontWeight: 700 }}>
          {formatPrice(subcategory.basePrice)}
        </Box>
      </Typography>
    </Box>
  );
}
