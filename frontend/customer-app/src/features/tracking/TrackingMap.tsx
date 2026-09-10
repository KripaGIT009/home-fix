import type { ReactNode } from 'react';
import { Box } from '@mui/material';
import NavigationRoundedIcon from '@mui/icons-material/NavigationRounded';
import HomeRoundedIcon from '@mui/icons-material/HomeRounded';
import type { Coordinates } from './api';

/**
 * A dependency-free map visualization for live tracking. Rather than pull in a
 * full mapping library, this plots the Provider and the destination on a simple
 * framed canvas whose extent is derived from the two coordinates, so the
 * relative position and movement of the Provider are visible and update in
 * real time. When the location is stale the marker is dimmed.
 */
export function TrackingMap({
  provider,
  destination,
  stale,
}: {
  provider: Coordinates;
  destination?: Coordinates;
  stale: boolean;
}) {
  // Compute a bounding box around the points, padded so markers aren't flush
  // against the edges. When there's only the provider point, center on it.
  const points = destination ? [provider, destination] : [provider];
  const lats = points.map((p) => p.latitude);
  const lngs = points.map((p) => p.longitude);
  const minLat = Math.min(...lats);
  const maxLat = Math.max(...lats);
  const minLng = Math.min(...lngs);
  const maxLng = Math.max(...lngs);

  const latSpan = Math.max(maxLat - minLat, 0.005);
  const lngSpan = Math.max(maxLng - minLng, 0.005);
  const pad = 0.2;

  const project = (coord: Coordinates): { x: number; y: number } => {
    const x = ((coord.longitude - minLng) / lngSpan) * (1 - 2 * pad) + pad;
    // Latitude increases upward, but SVG y increases downward — invert.
    const y = 1 - (((coord.latitude - minLat) / latSpan) * (1 - 2 * pad) + pad);
    return { x: x * 100, y: y * 100 };
  };

  const providerPos = project(provider);
  const destPos = destination ? project(destination) : null;

  return (
    <Box
      role="img"
      aria-label="Map showing the provider's current location"
      sx={{
        position: 'relative',
        width: '100%',
        aspectRatio: '16 / 10',
        borderRadius: 2,
        overflow: 'hidden',
        bgcolor: 'grey.100',
        border: '1px solid',
        borderColor: 'divider',
      }}
    >
      <Box
        component="svg"
        viewBox="0 0 100 100"
        preserveAspectRatio="none"
        sx={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }}
      >
        {/* Subtle grid to read the plane as a map. */}
        {[20, 40, 60, 80].map((n) => (
          <line key={`h${n}`} x1={0} y1={n} x2={100} y2={n} stroke="#e0e0e0" strokeWidth={0.3} />
        ))}
        {[20, 40, 60, 80].map((n) => (
          <line key={`v${n}`} x1={n} y1={0} x2={n} y2={100} stroke="#e0e0e0" strokeWidth={0.3} />
        ))}
        {destPos ? (
          <line
            x1={providerPos.x}
            y1={providerPos.y}
            x2={destPos.x}
            y2={destPos.y}
            stroke="#1976d2"
            strokeWidth={0.6}
            strokeDasharray="2 1.5"
          />
        ) : null}
      </Box>

      {destPos ? (
        <MapMarker x={destPos.x} y={destPos.y} label="Your location">
          <HomeRoundedIcon sx={{ color: 'text.secondary' }} />
        </MapMarker>
      ) : null}

      <MapMarker x={providerPos.x} y={providerPos.y} label="Provider location">
        <NavigationRoundedIcon
          sx={{
            color: stale ? 'text.disabled' : 'primary.main',
            filter: 'drop-shadow(0 1px 2px rgba(0,0,0,0.3))',
          }}
        />
      </MapMarker>
    </Box>
  );
}

function MapMarker({
  x,
  y,
  label,
  children,
}: {
  x: number;
  y: number;
  label: string;
  children: ReactNode;
}) {
  return (
    <Box
      aria-label={label}
      sx={{
        position: 'absolute',
        left: `${x}%`,
        top: `${y}%`,
        transform: 'translate(-50%, -50%)',
        display: 'flex',
        transition: 'left 1s ease, top 1s ease',
      }}
    >
      {children}
    </Box>
  );
}
