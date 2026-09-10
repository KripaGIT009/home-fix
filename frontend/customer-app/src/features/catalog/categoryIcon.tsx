import type { SvgIconProps } from '@mui/material';
import PlumbingRoundedIcon from '@mui/icons-material/PlumbingRounded';
import ElectricalServicesRoundedIcon from '@mui/icons-material/ElectricalServicesRounded';
import CleaningServicesRoundedIcon from '@mui/icons-material/CleaningServicesRounded';
import CarpenterRoundedIcon from '@mui/icons-material/CarpenterRounded';
import FormatPaintRoundedIcon from '@mui/icons-material/FormatPaintRounded';
import AcUnitRoundedIcon from '@mui/icons-material/AcUnitRounded';
import PestControlRoundedIcon from '@mui/icons-material/PestControlRounded';
import HandymanRoundedIcon from '@mui/icons-material/HandymanRounded';
import type { ComponentType } from 'react';

/**
 * Maps a server-defined category `icon` key to an MUI icon. The catalog is
 * database-driven (Requirement 3.1), so unknown keys fall back to a generic
 * handyman icon rather than failing.
 */
const ICON_BY_KEY: Record<string, ComponentType<SvgIconProps>> = {
  plumbing: PlumbingRoundedIcon,
  electrical: ElectricalServicesRoundedIcon,
  cleaning: CleaningServicesRoundedIcon,
  carpentry: CarpenterRoundedIcon,
  painting: FormatPaintRoundedIcon,
  ac: AcUnitRoundedIcon,
  hvac: AcUnitRoundedIcon,
  appliance: AcUnitRoundedIcon,
  pest: PestControlRoundedIcon,
  pestcontrol: PestControlRoundedIcon,
};

interface CategoryIconProps extends SvgIconProps {
  iconKey?: string | undefined;
  /**
   * Category name, used when the catalog row carries no icon key. Categories
   * are Admin-created and the icon field is optional, so without this fallback
   * every tile on the Home grid would render the same generic icon.
   */
  categoryName?: string | undefined;
}

/** Normalizes a server value or category name into an ICON_BY_KEY lookup key. */
function toKey(value: string | undefined): string {
  return value?.toLowerCase().replace(/[^a-z]/g, '') ?? '';
}

/** Finds an icon whose key appears in the given text, e.g. "AC Repair" -> ac. */
function matchByName(categoryName: string | undefined): ComponentType<SvgIconProps> | undefined {
  const haystack = toKey(categoryName);
  if (!haystack) return undefined;
  const key = Object.keys(ICON_BY_KEY).find((candidate) => haystack.includes(candidate));
  return key ? ICON_BY_KEY[key] : undefined;
}

/** Renders the icon for a category, defaulting to a handyman icon. */
export function CategoryIcon({ iconKey, categoryName, ...props }: CategoryIconProps) {
  const IconComponent =
    ICON_BY_KEY[toKey(iconKey)] ?? matchByName(categoryName) ?? HandymanRoundedIcon;
  return <IconComponent {...props} />;
}
