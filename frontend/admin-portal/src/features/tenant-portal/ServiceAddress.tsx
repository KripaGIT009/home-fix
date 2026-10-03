import { Link, Stack, Typography } from '@mui/material';
import PlaceRoundedIcon from '@mui/icons-material/PlaceRounded';
import OpenInNewRoundedIcon from '@mui/icons-material/OpenInNewRounded';
import { formatCoordinates, mapLink } from '@features/tenants/model';
import type { TenantBooking } from './api';

/**
 * Where the job is: the customer's address as entered, and a map link on its
 * coordinates (Requirement MT-5.1), so the admin can judge which team member
 * is close enough. Either part may be missing when customer-service could not
 * be reached; the row still renders and says so.
 */
export function ServiceAddress({
  booking,
  minWidth = 0,
}: {
  booking: TenantBooking;
  /** Keeps a table column from squeezing the address into a word per line. */
  minWidth?: number;
}) {
  const { address, coordinates } = booking;
  return (
    <Stack direction="row" spacing={0.75} alignItems="flex-start" sx={{ minWidth }}>
      <PlaceRoundedIcon sx={{ fontSize: 16, mt: 0.25, color: 'text.secondary' }} aria-hidden />
      <Stack spacing={0.25} sx={{ minWidth: 0 }}>
        <Typography variant="body2" sx={{ overflowWrap: 'anywhere' }}>
          {address ?? 'Address unavailable right now'}
        </Typography>
        {coordinates ? (
          <Link
            href={mapLink(coordinates.latitude, coordinates.longitude)}
            target="_blank"
            rel="noopener noreferrer"
            variant="caption"
            sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5, width: 'fit-content' }}
            aria-label={`Open ${booking.reference} location on the map`}
          >
            {formatCoordinates(coordinates.latitude, coordinates.longitude)}
            <OpenInNewRoundedIcon sx={{ fontSize: 12 }} />
          </Link>
        ) : null}
      </Stack>
    </Stack>
  );
}
