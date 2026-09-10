import { brand } from '@lib/theme';

/**
 * Per-category artwork for the marketplace-style tiles on the Home screen.
 *
 * The Service Catalog is Admin-driven and carries no imagery, so each category
 * gets a deterministic colour pair instead of a photo: a soft wash for the tile
 * and a saturated tone for the icon. Matching is by name substring, so an
 * Admin-created category lands on a sensible palette without a code change, and
 * anything unrecognised falls back to the brand blue.
 */
export interface CategoryArt {
  /** Tile background — a soft wash the icon sits on. */
  wash: string;
  /** Icon and accent colour, dark enough to read on {@link wash}. */
  accent: string;
}

const ART_BY_KEY: Record<string, CategoryArt> = {
  cleaning: { wash: '#E0F2FE', accent: '#0369A1' },
  plumbing: { wash: '#DBEAFE', accent: '#1D4ED8' },
  electrical: { wash: '#FEF3C7', accent: '#B45309' },
  appliance: { wash: '#E0E7FF', accent: '#4338CA' },
  ac: { wash: '#CFFAFE', accent: '#0E7490' },
  hvac: { wash: '#CFFAFE', accent: '#0E7490' },
  carpentry: { wash: '#FFEDD5', accent: '#C2410C' },
  painting: { wash: '#FCE7F3', accent: '#BE185D' },
  pest: { wash: '#DCFCE7', accent: '#15803D' },
  salon: { wash: '#FAE8FF', accent: '#A21CAF' },
  spa: { wash: '#FAE8FF', accent: '#A21CAF' },
};

const FALLBACK: CategoryArt = { wash: brand.accentSoft, accent: brand.accent };

/** Resolves the artwork for a category by its icon key or name. */
export function categoryArt(iconKey?: string, categoryName?: string): CategoryArt {
  const normalise = (value?: string) => value?.toLowerCase().replace(/[^a-z]/g, '') ?? '';

  const direct = ART_BY_KEY[normalise(iconKey)];
  if (direct) return direct;

  const haystack = normalise(categoryName);
  const key = Object.keys(ART_BY_KEY).find((candidate) => haystack.includes(candidate));
  return key ? (ART_BY_KEY[key] ?? FALLBACK) : FALLBACK;
}
