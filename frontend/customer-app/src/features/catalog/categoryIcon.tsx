import type { ComponentType } from 'react';
import type { SvgIconProps } from '@mui/material';
import PlumbingRoundedIcon from '@mui/icons-material/PlumbingRounded';
import ElectricBoltRoundedIcon from '@mui/icons-material/ElectricBoltRounded';
import CleaningServicesRoundedIcon from '@mui/icons-material/CleaningServicesRounded';
import CarpenterRoundedIcon from '@mui/icons-material/CarpenterRounded';
import FormatPaintRoundedIcon from '@mui/icons-material/FormatPaintRounded';
import AcUnitRoundedIcon from '@mui/icons-material/AcUnitRounded';
import PestControlRoundedIcon from '@mui/icons-material/PestControlRounded';
import KitchenRoundedIcon from '@mui/icons-material/KitchenRounded';
import ContentCutRoundedIcon from '@mui/icons-material/ContentCutRounded';
import HandymanRoundedIcon from '@mui/icons-material/HandymanRounded';
import { matchCategoryKey } from './categoryArt';

/**
 * Maps a category to one icon from a single family (MUI Rounded), so every
 * tile in the storefront reads as one set. The catalog is database-driven
 * (Requirement 3.1), so unknown categories fall back to a generic handyman.
 */
const ICON_BY_KEY: Record<string, ComponentType<SvgIconProps>> = {
  plumbing: PlumbingRoundedIcon,
  electrical: ElectricBoltRoundedIcon,
  cleaning: CleaningServicesRoundedIcon,
  carpentry: CarpenterRoundedIcon,
  painting: FormatPaintRoundedIcon,
  ac: AcUnitRoundedIcon,
  hvac: AcUnitRoundedIcon,
  appliance: KitchenRoundedIcon,
  pest: PestControlRoundedIcon,
  salon: ContentCutRoundedIcon,
  spa: ContentCutRoundedIcon,
};

interface CategoryIconProps extends SvgIconProps {
  iconKey?: string | undefined;
  /**
   * Category name, used when the catalog row carries no icon key. Categories
   * are Admin-created and the icon field is optional, so without this fallback
   * every tile would render the same generic icon.
   */
  categoryName?: string | undefined;
}

/** Renders the icon for a category, defaulting to a handyman icon. */
export function CategoryIcon({ iconKey, categoryName, ...props }: CategoryIconProps) {
  const key = matchCategoryKey(iconKey, categoryName);
  const IconComponent = (key ? ICON_BY_KEY[key] : undefined) ?? HandymanRoundedIcon;
  return <IconComponent {...props} />;
}
