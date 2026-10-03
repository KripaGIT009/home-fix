import type { ReactNode } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { Box, ButtonBase, Typography } from '@mui/material';
import { alpha, darken } from '@mui/material/styles';
import BoltRoundedIcon from '@mui/icons-material/BoltRounded';
import TimerRoundedIcon from '@mui/icons-material/TimerRounded';
import { formatPrice, formatDuration } from '@lib/format';
import { brand, radius } from '@lib/theme';
import { CategoryIcon } from './categoryIcon';
import { categoryArt } from './categoryArt';
import { Rail } from './Rail';
import { SectionHeading } from './SectionHeading';
import { pluralise, QUICK_FIX_MAX_MINUTES, type ServiceMatch } from './storefront';

/** One promo banner. Every word and number on it comes from the catalog. */
interface Spotlight {
  key: string;
  title: string;
  caption: string;
  actionLabel: string;
  accent: string;
  art: ReactNode;
  /** A route to open, or an in-page action. */
  to?: string;
  onSelect?: () => void;
}

/**
 * The promo row: wide gradient banners generated from the catalog — 24×7
 * help when any service offers it, the lowest "from" price in each category,
 * and how many jobs fit in an hour. There are no offers or discounts in the
 * system, so the banners promote real services rather than invented deals.
 */
export function SpotlightCarousel({
  cheapest,
  emergencyCount,
  quickFixes,
  onEmergency,
  onQuickFixes,
}: {
  /** The lowest-priced service in each category. */
  cheapest: ServiceMatch[];
  emergencyCount: number;
  quickFixes: ServiceMatch[];
  onEmergency: () => void;
  onQuickFixes: () => void;
}) {
  const categoryBanners: Spotlight[] = cheapest.map(({ category, subcategory }) => ({
    key: subcategory.id,
    title: `${subcategory.name} from ${formatPrice(subcategory.basePrice)}`,
    caption: `${category.name} · about ${formatDuration(subcategory.estimatedDurationMin)}`,
    actionLabel: 'Book now',
    accent: categoryArt(category.icon, category.name).accent,
    art: <CategoryIcon iconKey={category.icon} categoryName={category.name} />,
    to: `/book/${subcategory.id}`,
  }));

  const emergencyBanner: Spotlight[] =
    emergencyCount > 0
      ? [
          {
            key: 'emergency',
            title: 'Emergency help, 24×7',
            caption: `${pluralise(emergencyCount, 'service')} you can book any hour`,
            actionLabel: 'View',
            accent: brand.red,
            art: <BoltRoundedIcon />,
            onSelect: onEmergency,
          },
        ]
      : [];

  const quickBanner: Spotlight[] =
    quickFixes.length > 0
      ? [
          {
            key: 'quick',
            title: `Done in ${formatDuration(QUICK_FIX_MAX_MINUTES)} or less`,
            caption: `${pluralise(quickFixes.length, 'quick fix', 'quick fixes')}, from ${formatDuration(
              quickFixes[0]?.subcategory.estimatedDurationMin ?? 0,
            )}`,
            actionLabel: 'View',
            accent: brand.accent,
            art: <TimerRoundedIcon />,
            onSelect: onQuickFixes,
          },
        ]
      : [];

  // Interleave so neighbouring banners never share a colour family.
  const [first, ...others] = categoryBanners;
  const banners = [...emergencyBanner, ...(first ? [first] : []), ...quickBanner, ...others];
  if (banners.length === 0) return null;

  return (
    <Box component="section" aria-labelledby="spotlight-title">
      <SectionHeading id="spotlight-title" title="In the spotlight" />
      <Rail label="Spotlight">
        {banners.map((banner) => (
          <Box
            key={banner.key}
            role="listitem"
            sx={{
              width: {
                xs: '84%',
                sm: 'calc((100% - 16px) / 2)',
                md: 'calc((100% - 32px) / 3)',
              },
            }}
          >
            <Banner banner={banner} />
          </Box>
        ))}
      </Rail>
    </Box>
  );
}

function Banner({ banner }: { banner: Spotlight }) {
  const deep = darken(banner.accent, 0.45);
  const linkProps = banner.to
    ? { component: RouterLink, to: banner.to }
    : { onClick: banner.onSelect };

  return (
    <ButtonBase
      {...linkProps}
      focusRipple
      sx={{
        position: 'relative',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'flex-start',
        justifyContent: 'space-between',
        width: '100%',
        aspectRatio: '2.2 / 1',
        p: { xs: 2, md: 2.5 },
        overflow: 'hidden',
        textAlign: 'left',
        borderRadius: `${radius.md}px`,
        color: '#FFFFFF',
        // Darkest under the text, so white copy keeps its contrast on every accent.
        background: `linear-gradient(105deg, ${deep} 0%, ${darken(banner.accent, 0.2)} 55%, ${banner.accent} 100%)`,
        '&.Mui-focusVisible': {
          outline: `2px solid ${brand.accent}`,
          outlineOffset: 3,
        },
        '&:hover .banner-cta': { bgcolor: alpha('#FFFFFF', 0.88) },
        '&:hover .banner-art': { transform: 'rotate(-8deg) scale(1.04)' },
      }}
    >
      <Box
        aria-hidden
        sx={{
          position: 'absolute',
          right: '-12%',
          top: '-30%',
          width: '62%',
          aspectRatio: '1 / 1',
          borderRadius: '50%',
          bgcolor: alpha('#FFFFFF', 0.08),
        }}
      />
      <Box
        className="banner-art"
        aria-hidden
        sx={{
          position: 'absolute',
          right: '-4%',
          bottom: '-18%',
          height: '105%',
          aspectRatio: '1 / 1',
          display: 'grid',
          placeItems: 'center',
          color: alpha('#FFFFFF', 0.22),
          transform: 'rotate(-12deg)',
          transition: 'transform .3s ease',
          '& .MuiSvgIcon-root': { width: '100%', height: '100%', fontSize: 'inherit' },
        }}
      >
        {banner.art}
      </Box>

      <Box sx={{ position: 'relative', maxWidth: '68%' }}>
        <Typography
          component="span"
          sx={{
            display: 'block',
            fontWeight: 800,
            fontSize: { xs: '1.0625rem', md: '1.1875rem' },
            lineHeight: 1.25,
            letterSpacing: '-0.015em',
          }}
        >
          {banner.title}
        </Typography>
        <Typography
          component="span"
          sx={{
            display: 'block',
            mt: 0.5,
            fontSize: '0.8125rem',
            lineHeight: 1.4,
            color: alpha('#FFFFFF', 0.88),
          }}
        >
          {banner.caption}
        </Typography>
      </Box>
      <Box
        component="span"
        className="banner-cta"
        sx={{
          position: 'relative',
          px: 1.5,
          py: 0.5,
          borderRadius: `${radius.sm}px`,
          bgcolor: '#FFFFFF',
          color: brand.ink,
          fontSize: '0.8125rem',
          fontWeight: 700,
          transition: 'background-color .2s',
        }}
      >
        {banner.actionLabel}
      </Box>
    </ButtonBase>
  );
}
