import { brand } from '@lib/theme';

/**
 * Per-category colour for the storefront's icon tiles.
 *
 * The Service Catalog is Admin-driven and carries no imagery, so each category
 * gets a deterministic, harmonised colour pair instead of a photo: a soft wash
 * for the tile and a deeper tone for the icon. Matching is by key or name, so
 * an Admin-created category lands on a sensible palette without a code change,
 * and anything unrecognised falls back to the brand blue.
 */
export interface CategoryArt {
  /** Tile background — a soft wash the icon sits on. */
  wash: string;
  /** Icon and accent colour, dark enough to read on {@link wash}. */
  accent: string;
}

/** Ordered so longer, more specific keys win over short ones like "ac". */
const ART_BY_KEY: ReadonlyArray<[string, CategoryArt]> = [
  ['appliance', { wash: '#EEF0FE', accent: '#4A4FD1' }],
  ['cleaning', { wash: '#E7F6F7', accent: '#0B7A83' }],
  ['plumbing', { wash: '#EAF1FE', accent: '#1F56D0' }],
  ['electrical', { wash: '#FFF4DE', accent: '#A35F00' }],
  ['carpentry', { wash: '#FBEFE6', accent: '#A64B12' }],
  ['painting', { wash: '#FCEEF5', accent: '#B0306F' }],
  ['pest', { wash: '#EAF7EF', accent: '#18794E' }],
  ['salon', { wash: '#F6EEFC', accent: '#8A3FB8' }],
  ['spa', { wash: '#F6EEFC', accent: '#8A3FB8' }],
  ['hvac', { wash: '#E6F5FB', accent: '#0A6E99' }],
  ['ac', { wash: '#E6F5FB', accent: '#0A6E99' }],
];

const FALLBACK: CategoryArt = { wash: brand.accentSoft, accent: brand.accent };

const normalise = (value?: string) => value?.toLowerCase().replace(/[^a-z]/g, '') ?? '';

/** Finds the first key matching a category's icon key or name. */
export function matchCategoryKey(iconKey?: string, categoryName?: string): string | undefined {
  const direct = normalise(iconKey);
  if (direct && ART_BY_KEY.some(([key]) => key === direct)) return direct;
  const words = (categoryName ?? '')
    .toLowerCase()
    .split(/[^a-z]+/)
    .filter(Boolean);
  const joined = normalise(categoryName);
  return ART_BY_KEY.find(([key]) =>
    // Short keys must match a whole word ("AC Repair"), longer ones a substring.
    key.length <= 3 ? words.includes(key) : joined.includes(key),
  )?.[0];
}

/** Resolves the artwork for a category by its icon key or name. */
export function categoryArt(iconKey?: string, categoryName?: string): CategoryArt {
  const key = matchCategoryKey(iconKey, categoryName);
  return ART_BY_KEY.find(([candidate]) => candidate === key)?.[1] ?? FALLBACK;
}
