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
import AirRoundedIcon from '@mui/icons-material/AirRounded';
import ElectricalServicesRoundedIcon from '@mui/icons-material/ElectricalServicesRounded';
import LightRoundedIcon from '@mui/icons-material/LightRounded';
import WaterDropRoundedIcon from '@mui/icons-material/WaterDropRounded';
import BathtubRoundedIcon from '@mui/icons-material/BathtubRounded';
import ShowerRoundedIcon from '@mui/icons-material/ShowerRounded';
import CountertopsRoundedIcon from '@mui/icons-material/CountertopsRounded';
import WeekendRoundedIcon from '@mui/icons-material/WeekendRounded';
import LocalLaundryServiceRoundedIcon from '@mui/icons-material/LocalLaundryServiceRounded';
import MicrowaveRoundedIcon from '@mui/icons-material/MicrowaveRounded';
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

/**
 * Service-level icons, matched on words in the service name. Two services in
 * one category would otherwise share an identical tile; the name is the only
 * thing the catalog says about a service, so it is what tells them apart.
 * Ordered so specific words win; short words must match a whole word.
 */
const ICON_BY_SERVICE_WORD: ReadonlyArray<[string, ComponentType<SvgIconProps>]> = [
  ['washing', LocalLaundryServiceRoundedIcon],
  ['refrigerator', KitchenRoundedIcon],
  ['fridge', KitchenRoundedIcon],
  ['microwave', MicrowaveRoundedIcon],
  ['switch', ElectricalServicesRoundedIcon],
  ['wiring', ElectricalServicesRoundedIcon],
  ['socket', ElectricalServicesRoundedIcon],
  ['light', LightRoundedIcon],
  ['fan', AirRoundedIcon],
  ['leak', WaterDropRoundedIcon],
  ['bathroom', BathtubRoundedIcon],
  ['shower', ShowerRoundedIcon],
  ['kitchen', CountertopsRoundedIcon],
  ['sofa', WeekendRoundedIcon],
  ['ac', AcUnitRoundedIcon],
];

interface ServiceIconProps extends CategoryIconProps {
  serviceName: string;
}

/** Renders the icon for a service, falling back to its category's icon. */
export function ServiceIcon({ serviceName, iconKey, categoryName, ...props }: ServiceIconProps) {
  const words = serviceName
    .toLowerCase()
    .split(/[^a-z]+/)
    .filter(Boolean);
  const joined = words.join('');
  const IconComponent = ICON_BY_SERVICE_WORD.find(([word]) =>
    word.length <= 3 ? words.includes(word) : joined.includes(word),
  )?.[1];
  if (!IconComponent) {
    return <CategoryIcon iconKey={iconKey} categoryName={categoryName} {...props} />;
  }
  return <IconComponent {...props} />;
}
