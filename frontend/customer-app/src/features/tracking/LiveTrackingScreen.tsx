import { useMemo } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Alert,
  Avatar,
  Badge,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Stack,
  Typography,
} from '@mui/material';
import VerifiedRoundedIcon from '@mui/icons-material/VerifiedRounded';
import AccessTimeRoundedIcon from '@mui/icons-material/AccessTimeRounded';
import WarningAmberRoundedIcon from '@mui/icons-material/WarningAmberRounded';
import FiberManualRecordRoundedIcon from '@mui/icons-material/FiberManualRecordRounded';
import StarRounded from '@mui/icons-material/StarRounded';
import { AppShell } from '@components/AppShell';
import { QueryStateView } from '@components/QueryStateView';
import { formatEtaMinutes, formatTimeAgo } from '@lib/format';
import { brand } from '@lib/theme';
import { TrackingMap } from './TrackingMap';
import { ChatButton } from './ChatButton';
import { useLiveLocation, useNowTick } from './hooks';
import { STALE_LOCATION_THRESHOLD_MS, TRACKING_TICK_MS } from './constants';
import type { LocationSnapshot } from './api';

/**
 * Live Tracking screen (Requirement 10, Requirement 28.7). Seeds from the
 * Location Service snapshot then streams real-time updates over SSE (folded
 * into the query cache by useLiveLocation). Shows the provider on a map, an ETA
 * countdown, a provider info card, and — per Requirement 10.7 — a visual
 * indicator with the last-updated time when the location is stale (> 60 s).
 * Includes an in-app chat button (Requirement 18).
 *
 * The bottom navigation is hidden: this is a screen the customer watches, and
 * messaging the provider is the only action that should compete for a tap.
 */
export function LiveTrackingScreen() {
  const { bookingId = '' } = useParams();
  const navigate = useNavigate();

  const { snapshot, isLoading, isError, error, connected, refetch } = useLiveLocation(bookingId);
  const now = useNowTick(TRACKING_TICK_MS);

  const staleness = useMemo(() => {
    if (!snapshot) return { stale: false, agoLabel: '' };
    const updatedAt = new Date(snapshot.updatedAt).getTime();
    const ageMs = now - updatedAt;
    return {
      stale: Number.isFinite(ageMs) && ageMs > STALE_LOCATION_THRESHOLD_MS,
      agoLabel: formatTimeAgo(snapshot.updatedAt, now),
    };
  }, [snapshot, now]);

  return (
    <AppShell title="Live tracking" hideBottomNav action={<ConnectionChip connected={connected} />}>
      <QueryStateView
        isLoading={isLoading}
        isError={isError}
        error={error}
        onRetry={refetch}
        isEmpty={!snapshot}
        emptyMessage="Tracking will begin once your provider is on the way."
      >
        {snapshot ? (
          <Stack spacing={2}>
            {staleness.stale ? (
              <Alert severity="warning" icon={<WarningAmberRoundedIcon />} role="status">
                Location may be out of date — last updated {staleness.agoLabel}.
              </Alert>
            ) : null}

            <Card sx={{ overflow: 'hidden' }}>
              <TrackingMap
                provider={snapshot.coordinates}
                {...(snapshot.destination ? { destination: snapshot.destination } : {})}
                stale={staleness.stale}
              />
              <CardContent
                sx={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 1.5,
                  borderTop: 1,
                  borderColor: 'divider',
                }}
              >
                <Box
                  sx={{
                    width: 44,
                    height: 44,
                    borderRadius: 2.5,
                    display: 'grid',
                    placeItems: 'center',
                    bgcolor: brand.accentSoft,
                    color: 'primary.main',
                  }}
                >
                  <AccessTimeRoundedIcon aria-hidden />
                </Box>
                <Box sx={{ flexGrow: 1 }}>
                  <Typography variant="caption" color="text.secondary" display="block">
                    Estimated arrival
                  </Typography>
                  <Typography variant="h5" component="p" fontWeight={800}>
                    {formatEtaMinutes(snapshot.etaMinutes)}
                  </Typography>
                </Box>
                <Typography
                  variant="caption"
                  color={staleness.stale ? 'warning.main' : 'text.secondary'}
                  sx={{ textAlign: 'right' }}
                >
                  Updated
                  <br />
                  {staleness.agoLabel}
                </Typography>
              </CardContent>
            </Card>

            {snapshot.provider ? (
              <ProviderInfoCard
                provider={snapshot.provider}
                chat={
                  <ChatButton
                    bookingId={bookingId}
                    onOpen={() => navigate(`/bookings/${bookingId}/chat`)}
                  />
                }
              />
            ) : (
              <ChatButton
                bookingId={bookingId}
                onOpen={() => navigate(`/bookings/${bookingId}/chat`)}
              />
            )}

            <Button variant="text" onClick={() => navigate(`/bookings/${bookingId}`)}>
              View booking details
            </Button>
          </Stack>
        ) : null}
      </QueryStateView>
    </AppShell>
  );
}

function ConnectionChip({ connected }: { connected: boolean }) {
  return (
    <Chip
      size="small"
      variant="outlined"
      color={connected ? 'success' : 'default'}
      icon={<FiberManualRecordRoundedIcon sx={{ fontSize: 11 }} />}
      label={connected ? 'Live' : 'Reconnecting…'}
    />
  );
}

function ProviderInfoCard({
  provider,
  chat,
}: {
  provider: NonNullable<LocationSnapshot['provider']>;
  chat: React.ReactNode;
}) {
  const initials = provider.displayName
    .split(' ')
    .map((part) => part.charAt(0))
    .slice(0, 2)
    .join('')
    .toUpperCase();

  return (
    <Card>
      <CardContent>
        <Stack direction="row" spacing={2} alignItems="center">
          <Badge
            overlap="circular"
            anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
            badgeContent={
              provider.verified ? (
                <VerifiedRoundedIcon
                  sx={{ fontSize: 18, color: 'success.main', bgcolor: '#fff', borderRadius: '50%' }}
                  aria-hidden
                />
              ) : null
            }
          >
            <Avatar aria-hidden sx={{ width: 52, height: 52, bgcolor: 'primary.main' }}>
              {initials}
            </Avatar>
          </Badge>
          <Box sx={{ flexGrow: 1, minWidth: 0 }}>
            <Typography variant="subtitle1" fontWeight={700}>
              {provider.displayName}
            </Typography>
            <Stack direction="row" spacing={0.25} alignItems="center">
              <StarRounded sx={{ fontSize: 17, color: 'warning.main' }} aria-hidden />
              <Typography variant="body2" color="text.secondary" fontWeight={600}>
                {provider.rating.toFixed(1)} rating
              </Typography>
            </Stack>
          </Box>
        </Stack>

        <Box sx={{ mt: 2 }}>{chat}</Box>
      </CardContent>
    </Card>
  );
}
