import type { ReactNode } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { Box, ButtonBase, Typography } from '@mui/material';
import BoltRoundedIcon from '@mui/icons-material/BoltRounded';
import { formatPrice } from '@lib/format';
import { brand, radius } from '@lib/theme';
import { CategoryIcon } from './categoryIcon';
import { categoryArt } from './categoryArt';
import { ServiceArt } from './ServiceArt';
import { TrustList } from './TrustList';
import type { ServiceCategory } from './api';
import { pluralise, type ServiceMatch } from './storefront';

/**
 * The top of the storefront: a headline, a "What are you looking for?" card
 * with one tile per category (plus 24×7 help when any service offers it), the
 * trust strip, and on the right a 2×2 mosaic of illustrated services.
 */
export function HeroSection({
  categories,
  featured,
  emergencyCount,
  onEmergency,
}: {
  categories: ServiceCategory[];
  /** Up to four services for the mosaic. */
  featured: ServiceMatch[];
  emergencyCount: number;
  onEmergency: () => void;
}) {
  return (
    <Box
      component="section"
      aria-labelledby="home-hero-title"
      sx={{
        display: 'grid',
        gridTemplateColumns: { xs: '1fr', md: 'minmax(0, 1.1fr) minmax(0, 0.9fr)' },
        gap: { xs: 3, md: 5, lg: 7 },
        alignItems: 'center',
      }}
    >
      <Box>
        <Typography
          id="home-hero-title"
          variant="h1"
          sx={{
            fontSize: { xs: '1.75rem', md: '2.5rem' },
            fontWeight: 700,
            letterSpacing: '-0.03em',
            lineHeight: 1.15,
            maxWidth: 520,
          }}
        >
          Verified pros for every fix around the house
        </Typography>

        <Box
          sx={{
            mt: { xs: 2.5, md: 3.5 },
            p: { xs: 2, md: 3 },
            border: `1px solid ${brand.line}`,
            borderRadius: `${radius.md}px`,
            bgcolor: 'background.paper',
          }}
        >
          <Typography
            variant="h2"
            sx={{
              fontSize: { xs: '1rem', md: '1.125rem' },
              fontWeight: 600,
              letterSpacing: '-0.01em',
              color: 'text.secondary',
              mb: { xs: 1.75, md: 2.25 },
            }}
          >
            What are you looking for?
          </Typography>
          <Box
            component="ul"
            aria-label="Service categories"
            sx={{
              listStyle: 'none',
              m: 0,
              p: 0,
              display: 'grid',
              gridTemplateColumns: 'repeat(3, minmax(0, 1fr))',
              columnGap: { xs: 1.25, md: 2 },
              rowGap: { xs: 2, md: 2.5 },
            }}
          >
            {categories.map((category) => {
              const art = categoryArt(category.icon, category.name);
              return (
                <Box component="li" key={category.id}>
                  <CategoryTile
                    label={category.name}
                    wash={art.wash}
                    color={art.accent}
                    to={`/categories/${category.id}`}
                    icon={<CategoryIcon iconKey={category.icon} categoryName={category.name} />}
                  />
                </Box>
              );
            })}
            {emergencyCount > 0 ? (
              <Box component="li">
                <CategoryTile
                  label="Emergency"
                  description={`${pluralise(emergencyCount, 'service')} available 24×7`}
                  wash={brand.redSoft}
                  color={brand.red}
                  onSelect={onEmergency}
                  icon={<BoltRoundedIcon />}
                />
              </Box>
            ) : null}
          </Box>
        </Box>

        <Box sx={{ mt: { xs: 2.5, md: 3.5 } }}>
          <TrustList />
        </Box>
      </Box>

      {featured.length > 0 ? <Mosaic services={featured} /> : null}
    </Box>
  );
}

/** A category shortcut: a soft tile with the icon, the name centred below. */
function CategoryTile({
  label,
  description,
  wash,
  color,
  icon,
  to,
  onSelect,
}: {
  label: string;
  /** Extra context for screen readers, e.g. how many services are 24×7. */
  description?: string;
  wash: string;
  color: string;
  icon: ReactNode;
  to?: string;
  onSelect?: () => void;
}) {
  const action = to ? { component: RouterLink, to } : { onClick: onSelect };
  return (
    <ButtonBase
      {...action}
      {...(description ? { 'aria-label': `${label}: ${description}` } : {})}
      sx={{
        width: '100%',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'stretch',
        borderRadius: `${radius.md}px`,
        '&.Mui-focusVisible': { outline: `2px solid ${brand.accent}`, outlineOffset: 3 },
        '&:hover .category-tile-box': { filter: 'brightness(0.97)' },
        '&:hover .category-tile-box svg': { transform: 'scale(1.08)' },
      }}
    >
      <Box
        className="category-tile-box"
        aria-hidden
        sx={{
          height: { xs: 68, md: 76 },
          borderRadius: `${radius.md}px`,
          bgcolor: wash,
          color,
          display: 'grid',
          placeItems: 'center',
          transition: 'filter .2s',
          '& svg': { fontSize: { xs: 30, md: 34 }, transition: 'transform .2s ease' },
        }}
      >
        {icon}
      </Box>
      <Typography
        component="span"
        sx={{
          mt: 1,
          px: 0.25,
          fontSize: { xs: '0.75rem', md: '0.8125rem' },
          fontWeight: 500,
          lineHeight: 1.3,
          textAlign: 'center',
          color: 'text.primary',
        }}
      >
        {label}
      </Typography>
    </ButtonBase>
  );
}

/**
 * The reference shows a photo collage here; the catalog has no photos, so the
 * mosaic uses the same illustrated tiles as the service cards, each labelled
 * with its real name and "from" price and linking to its booking page.
 */
function Mosaic({ services }: { services: ServiceMatch[] }) {
  return (
    <Box
      component="ul"
      aria-label="Featured services"
      sx={{
        listStyle: 'none',
        m: 0,
        p: 0,
        display: 'grid',
        gridTemplateColumns: 'repeat(2, minmax(0, 1fr))',
        gap: { xs: 1.25, md: 2 },
      }}
    >
      {services.map(({ category, subcategory }) => (
        <Box component="li" key={subcategory.id}>
          <Box
            component={RouterLink}
            to={`/book/${subcategory.id}`}
            aria-label={`${subcategory.name}, from ${formatPrice(subcategory.basePrice)}`}
            sx={{
              display: 'block',
              borderRadius: `${radius.lg}px`,
              overflow: 'hidden',
              '&:focus-visible': { outline: `2px solid ${brand.accent}`, outlineOffset: 3 },
              '& .mosaic-art': { transition: 'transform .3s ease' },
              '&:hover .mosaic-art': { transform: 'scale(1.03)' },
            }}
          >
            <ServiceArt
              category={category}
              subcategory={subcategory}
              className="mosaic-art"
              plate={0.38}
              showBadge={false}
              sx={{ borderRadius: `${radius.lg}px`, aspectRatio: { xs: '1 / 0.8', md: '1 / 1' } }}
            >
              <Box
                sx={{
                  position: 'absolute',
                  left: { xs: 8, md: 12 },
                  bottom: { xs: 8, md: 12 },
                  maxWidth: 'calc(100% - 24px)',
                  px: { xs: 1, md: 1.5 },
                  py: { xs: 0.5, md: 0.75 },
                  borderRadius: `${radius.sm}px`,
                  bgcolor: 'background.paper',
                  boxShadow: '0 2px 8px -2px rgba(15, 15, 15, 0.14)',
                }}
              >
                <Typography
                  component="span"
                  noWrap
                  sx={{
                    display: 'block',
                    fontSize: { xs: '0.75rem', md: '0.8125rem' },
                    fontWeight: 700,
                    color: 'text.primary',
                    lineHeight: 1.35,
                  }}
                >
                  {subcategory.name}
                </Typography>
                <Typography
                  component="span"
                  sx={{
                    display: 'block',
                    fontSize: '0.75rem',
                    color: 'text.secondary',
                    lineHeight: 1.35,
                  }}
                >
                  from {formatPrice(subcategory.basePrice)}
                </Typography>
              </Box>
            </ServiceArt>
          </Box>
        </Box>
      ))}
    </Box>
  );
}
