import type { ReactNode } from 'react';
import { Box, Stack, Typography } from '@mui/material';
import { alpha, type SxProps, type Theme } from '@mui/material/styles';
import BoltRoundedIcon from '@mui/icons-material/BoltRounded';
import { brand, radius, visuallyHidden } from '@lib/theme';
import { ServiceIcon } from './categoryIcon';
import { categoryArt } from './categoryArt';
import type { ServiceCategory, ServiceSubcategory } from './api';

/**
 * Decorative backdrops for {@link ServiceArt}, in a 100×100 box. The catalog
 * has no photos, so each tile is an illustration: the category's wash, a few
 * soft shapes and the service's icon on a white plate. The layout is picked
 * from the service id so neighbouring tiles in a rail do not look stamped out.
 */
function Backdrop({ variant, accent }: { variant: number; accent: string }) {
  const soft = alpha(accent, 0.1);
  const mid = alpha(accent, 0.16);
  const line = alpha(accent, 0.22);
  return (
    <Box
      component="svg"
      viewBox="0 0 100 100"
      preserveAspectRatio="xMidYMid slice"
      aria-hidden
      focusable="false"
      sx={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }}
    >
      {variant === 0 ? (
        <>
          <circle cx="90" cy="10" r="34" fill={soft} />
          <circle cx="10" cy="92" r="16" fill={mid} />
          <circle cx="20" cy="22" r="6" fill="none" stroke={line} strokeWidth="1.6" />
        </>
      ) : variant === 1 ? (
        <>
          <circle cx="94" cy="90" r="38" fill={soft} />
          <circle cx="14" cy="14" r="10" fill={mid} />
          <circle cx="82" cy="20" r="4" fill={line} />
        </>
      ) : (
        <>
          <path d="M-10 70 Q 30 40 60 78 T 110 74 V 110 H -10 Z" fill={soft} />
          <circle cx="84" cy="18" r="12" fill="none" stroke={line} strokeWidth="1.6" />
          <circle cx="16" cy="24" r="4" fill={mid} />
        </>
      )}
    </Box>
  );
}

/** The array form of `sx`, so callers' styles can be appended to a component's own. */
type SxArray = Extract<SxProps<Theme>, ReadonlyArray<unknown>>;

/** Normalises an optional `sx` prop to a list, MUI's way of merging styles. */
const sxList = (sx: SxProps<Theme> | undefined): SxArray =>
  (sx === undefined ? [] : Array.isArray(sx) ? sx : [sx]) as SxArray;

/** Stable small hash so a service always gets the same backdrop. */
function variantFor(id: string): number {
  let hash = 0;
  for (let i = 0; i < id.length; i += 1) hash = (hash * 31 + id.charCodeAt(i)) >>> 0;
  return hash % 3;
}

interface ServiceArtProps {
  category: ServiceCategory | undefined;
  subcategory: ServiceSubcategory;
  /** Size of the white icon plate as a share of the tile's width. */
  plate?: number;
  /** Show the "24×7" badge in the top-left corner for emergency services. */
  showBadge?: boolean;
  /** Overlays such as a name pill, positioned by the caller. */
  children?: ReactNode;
  /** Hook for hover effects driven by an enclosing link. */
  className?: string;
  sx?: SxProps<Theme>;
}

/**
 * The illustrated thumbnail used wherever the reference storefront shows a
 * photo: square service cards, the hero mosaic and the category page rows.
 * Purely decorative — the service name is always rendered as text nearby.
 */
export function ServiceArt({
  category,
  subcategory,
  plate = 0.46,
  showBadge = true,
  children,
  className,
  sx,
}: ServiceArtProps) {
  const art = categoryArt(category?.icon, category?.name);
  return (
    <Box
      {...(className ? { className } : {})}
      sx={[
        {
          position: 'relative',
          overflow: 'hidden',
          aspectRatio: '1 / 1',
          borderRadius: `${radius.md}px`,
          bgcolor: art.wash,
        },
        ...sxList(sx),
      ]}
    >
      <Backdrop variant={variantFor(subcategory.id)} accent={art.accent} />
      <Box
        aria-hidden
        sx={{
          position: 'absolute',
          top: '50%',
          left: '50%',
          width: `${plate * 100}%`,
          aspectRatio: '1 / 1',
          transform: 'translate(-50%, -50%)',
          borderRadius: '50%',
          display: 'grid',
          placeItems: 'center',
          bgcolor: 'background.paper',
          color: art.accent,
          boxShadow: `0 10px 24px -10px ${alpha(art.accent, 0.45)}`,
          // Percentages of the plate, so the icon scales with every tile size.
          '& .MuiSvgIcon-root': { width: '52%', height: '52%', fontSize: 'inherit' },
        }}
      >
        <ServiceIcon
          serviceName={subcategory.name}
          iconKey={category?.icon}
          categoryName={category?.name}
        />
      </Box>
      {showBadge && subcategory.emergencyAvailable ? (
        <TwentyFourSevenBadge sx={{ position: 'absolute', top: 8, left: 8 }} />
      ) : null}
      {children}
    </Box>
  );
}

/** Marks a service that can be booked as an emergency, any hour. */
export function TwentyFourSevenBadge({ sx }: { sx?: SxProps<Theme> }) {
  return (
    <Stack
      direction="row"
      spacing={0.25}
      alignItems="center"
      sx={[
        {
          px: 0.75,
          py: 0.25,
          borderRadius: `${radius.pill}px`,
          bgcolor: brand.paper,
          color: brand.warmDark,
          boxShadow: '0 1px 2px rgba(15, 15, 15, 0.08)',
          flexShrink: 0,
        },
        ...sxList(sx),
      ]}
    >
      <BoltRoundedIcon sx={{ fontSize: 14, color: brand.warm }} aria-hidden />
      <Typography
        variant="caption"
        fontWeight={800}
        sx={{ fontSize: '0.6875rem', lineHeight: 1.5 }}
      >
        24×7
        <Box component="span" sx={visuallyHidden}>
          {' '}
          emergency booking
        </Box>
      </Typography>
    </Stack>
  );
}
