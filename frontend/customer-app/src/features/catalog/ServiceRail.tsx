import { Link as RouterLink } from 'react-router-dom';
import { Box, Link } from '@mui/material';
import { Rail } from './Rail';
import { SectionHeading } from './SectionHeading';
import { ServiceTile } from './ServiceTile';
import type { ServiceMatch } from './storefront';

/** Card width in a rail: about 2.2 cards on a phone, five across on desktop. */
const SERVICE_TILE_WIDTH = { xs: 164, sm: 180, md: 208 } as const;

/**
 * One storefront row: a heading with an optional "See all" link and a rail of
 * service cards. Cards keep a fixed width, so a category with only two
 * services reads as a short row rather than two stretched boxes.
 */
export function ServiceRail({
  id,
  title,
  caption,
  seeAllTo,
  seeAllLabel,
  services,
}: {
  id: string;
  title: string;
  caption?: string | undefined;
  /** Destination of the "See all" link; omitted when there is nowhere to go. */
  seeAllTo?: string;
  /** Accessible name for "See all", e.g. "See all Plumbing services". */
  seeAllLabel?: string;
  services: ServiceMatch[];
}) {
  const headingId = `${id}-title`;
  return (
    <Box component="section" id={id} aria-labelledby={headingId} sx={{ scrollMarginTop: 88 }}>
      <SectionHeading
        id={headingId}
        title={title}
        caption={caption}
        action={
          seeAllTo ? (
            <Link
              component={RouterLink}
              to={seeAllTo}
              underline="hover"
              variant="body2"
              fontWeight={600}
              aria-label={seeAllLabel}
            >
              See all
            </Link>
          ) : null
        }
      />
      <Rail label={title} arrowTop={`calc(${SERVICE_TILE_WIDTH.md / 2}px + 6px)`}>
        {services.map(({ category, subcategory }) => (
          <Box key={subcategory.id} role="listitem" sx={{ width: SERVICE_TILE_WIDTH }}>
            <ServiceTile category={category} subcategory={subcategory} />
          </Box>
        ))}
      </Rail>
    </Box>
  );
}
