import { Box, Card, CardActionArea, Stack, Typography } from '@mui/material';
import BoltRoundedIcon from '@mui/icons-material/BoltRounded';
import ScheduleRoundedIcon from '@mui/icons-material/ScheduleRounded';
import { IconTile } from '@components/StateViews';
import { formatDuration, formatPrice } from '@lib/format';
import { brand, radius, shadows } from '@lib/theme';
import { CategoryIcon } from './categoryIcon';
import { categoryArt } from './categoryArt';
import type { ServiceCategory, ServiceSubcategory } from './api';

/** A bookable service: name, category, duration, "from" price and a book cue. */
export function ServiceCard({
  category,
  subcategory,
  onSelect,
  showCategory = true,
}: {
  category: ServiceCategory | undefined;
  subcategory: ServiceSubcategory;
  onSelect: () => void;
  /** Show the category name in the meta line (off inside a category). */
  showCategory?: boolean;
}) {
  const art = categoryArt(category?.icon, category?.name);

  return (
    <Card
      sx={{
        height: '100%',
        transition: 'box-shadow .2s, border-color .2s',
        '&:hover': { boxShadow: shadows.raised, borderColor: brand.lineStrong },
        '&:hover .book-cue': { bgcolor: 'primary.dark' },
      }}
    >
      <CardActionArea
        onClick={onSelect}
        aria-label={`Book ${subcategory.name}, from ${formatPrice(subcategory.basePrice)}`}
        sx={{
          height: '100%',
          p: { xs: 2, md: 2.5 },
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'stretch',
        }}
      >
        <Stack direction="row" spacing={1.5} alignItems="flex-start">
          <IconTile size={44} bg={art.wash} color={art.accent}>
            <CategoryIcon iconKey={category?.icon} categoryName={category?.name} />
          </IconTile>
          <Box sx={{ flexGrow: 1, minWidth: 0 }}>
            <Typography variant="subtitle1" fontWeight={700} sx={{ lineHeight: 1.35 }}>
              {subcategory.name}
            </Typography>
            <Stack
              direction="row"
              spacing={0.75}
              alignItems="center"
              sx={{ color: 'text.secondary', mt: 0.25 }}
            >
              {showCategory && category ? (
                <>
                  <Typography variant="body2" noWrap>
                    {category.name}
                  </Typography>
                  <Box
                    component="span"
                    aria-hidden
                    sx={{ width: 3, height: 3, borderRadius: '50%', bgcolor: 'text.disabled' }}
                  />
                </>
              ) : null}
              <ScheduleRoundedIcon sx={{ fontSize: 15 }} aria-hidden />
              <Typography variant="body2" noWrap>
                {formatDuration(subcategory.estimatedDurationMin)}
              </Typography>
            </Stack>
          </Box>
          {subcategory.emergencyAvailable ? <TwentyFourSevenBadge /> : null}
        </Stack>

        {subcategory.description ? (
          <Typography
            variant="body2"
            color="text.secondary"
            sx={{
              mt: 1.5,
              display: '-webkit-box',
              WebkitLineClamp: 2,
              WebkitBoxOrient: 'vertical',
              overflow: 'hidden',
            }}
          >
            {subcategory.description}
          </Typography>
        ) : null}

        <Box sx={{ flexGrow: 1 }} />
        <Stack direction="row" alignItems="center" justifyContent="space-between" sx={{ mt: 2 }}>
          <Box>
            <Typography variant="caption" color="text.secondary" display="block" lineHeight={1.2}>
              from
            </Typography>
            <Typography variant="h5" component="p" fontWeight={800}>
              {formatPrice(subcategory.basePrice)}
            </Typography>
          </Box>
          <Box
            className="book-cue"
            aria-hidden
            sx={{
              px: 2,
              py: 0.875,
              borderRadius: `${radius.md}px`,
              bgcolor: 'primary.main',
              color: 'primary.contrastText',
              fontWeight: 700,
              fontSize: '0.875rem',
              transition: 'background-color .2s',
            }}
          >
            Book
          </Box>
        </Stack>
      </CardActionArea>
    </Card>
  );
}

export function TwentyFourSevenBadge() {
  return (
    <Stack
      direction="row"
      spacing={0.25}
      alignItems="center"
      sx={{
        px: 0.875,
        py: 0.25,
        borderRadius: `${radius.pill}px`,
        bgcolor: brand.warmSoft,
        color: brand.warmDark,
        flexShrink: 0,
      }}
    >
      <BoltRoundedIcon sx={{ fontSize: 14 }} aria-hidden />
      <Typography variant="caption" fontWeight={800} sx={{ fontSize: '0.75rem' }}>
        24×7
      </Typography>
    </Stack>
  );
}
