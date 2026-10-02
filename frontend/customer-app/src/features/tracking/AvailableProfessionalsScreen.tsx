import { useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import {
  Avatar,
  Badge,
  Box,
  Card,
  CardContent,
  Chip,
  Divider,
  Stack,
  Typography,
} from '@mui/material';
import VerifiedRoundedIcon from '@mui/icons-material/VerifiedRounded';
import PlaceRoundedIcon from '@mui/icons-material/PlaceRounded';
import AccessTimeRoundedIcon from '@mui/icons-material/AccessTimeRounded';
import WorkHistoryRoundedIcon from '@mui/icons-material/WorkHistoryRounded';
import StarRounded from '@mui/icons-material/StarRounded';
import { AppShell } from '@components/AppShell';
import { QueryStateView } from '@components/QueryStateView';
import { formatCurrency, formatDistanceKm, formatEtaMinutes } from '@lib/format';
import { brand, radius } from '@lib/theme';
import type { AvailableProfessional } from './api';
import { useAvailableProfessionals } from './hooks';

/** How the customer can order the candidate list. */
type SortKey = 'nearby' | 'rating' | 'eta';

const SORTS: { key: SortKey; label: string }[] = [
  { key: 'nearby', label: 'Nearby' },
  { key: 'rating', label: 'Rating' },
  { key: 'eta', label: 'ETA' },
];

/** Comparators matching each sort chip; all ascending except rating. */
const COMPARATORS: Record<SortKey, (a: AvailableProfessional, b: AvailableProfessional) => number> =
  {
    nearby: (a, b) => a.distanceKm - b.distanceKm,
    rating: (a, b) => b.rating - a.rating,
    eta: (a, b) => a.etaMinutes - b.etaMinutes,
  };

/**
 * Available Professionals list (Requirements 28.6, 28.7). Renders each eligible
 * Provider as a card showing the VERIFIED badge, display name, aggregate
 * rating, jobs completed, distance (km), ETA (min), and starting price.
 *
 * The list is informational. The Dispatch Engine offers the job to providers
 * itself and no service accepts a customer's choice of provider, so there is
 * nothing to select; the booking flow goes straight to live tracking.
 *
 * The list arrives ranked by dispatch fit; the sort chips let the customer
 * re-rank by the one attribute they care about without another round trip.
 */
export function AvailableProfessionalsScreen() {
  const { bookingId = '' } = useParams();

  const query = useAvailableProfessionals(bookingId);
  const [sortKey, setSortKey] = useState<SortKey>('nearby');

  const professionals = useMemo(() => {
    if (!query.data) return undefined;
    return [...query.data].sort(COMPARATORS[sortKey]);
  }, [query.data, sortKey]);

  return (
    <AppShell title="Professionals near you" width="full">
      <Stack spacing={2}>
        <Typography variant="body1" color="text.secondary">
          Verified pros near you, ranked by fit. We offer your job to them in turn.
        </Typography>

        <Stack direction="row" spacing={1}>
          {SORTS.map((sort) => (
            <Chip
              key={sort.key}
              label={sort.label}
              onClick={() => setSortKey(sort.key)}
              color={sortKey === sort.key ? 'primary' : 'default'}
              variant={sortKey === sort.key ? 'filled' : 'outlined'}
              aria-pressed={sortKey === sort.key}
            />
          ))}
        </Stack>

        <QueryStateView
          isLoading={query.isLoading}
          isError={query.isError}
          error={query.error}
          onRetry={() => void query.refetch()}
          isEmpty={!professionals || professionals.length === 0}
          emptyMessage="No professionals are available right now. We'll keep searching."
        >
          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: { xs: '1fr', md: 'repeat(2, minmax(0, 1fr))' },
              gap: { xs: 1.5, md: 2.5 },
            }}
          >
            {professionals?.map((professional) => (
              <ProfessionalCard key={professional.providerId} professional={professional} />
            ))}
          </Box>
        </QueryStateView>
      </Stack>
    </AppShell>
  );
}

function ProfessionalCard({ professional }: { professional: AvailableProfessional }) {
  const initials = professional.displayName
    .split(' ')
    .map((part) => part.charAt(0))
    .slice(0, 2)
    .join('')
    .toUpperCase();

  return (
    <Card>
      <CardContent>
        <Stack direction="row" spacing={2} alignItems="flex-start">
          <Badge
            overlap="circular"
            anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
            badgeContent={
              professional.verified ? (
                <VerifiedRoundedIcon
                  sx={{ fontSize: 18, color: 'success.main', bgcolor: '#fff', borderRadius: '50%' }}
                  aria-hidden
                />
              ) : null
            }
          >
            <Avatar
              aria-hidden
              sx={{ width: 52, height: 52, bgcolor: brand.accentSoft, color: 'primary.main' }}
            >
              {initials}
            </Avatar>
          </Badge>

          <Box sx={{ flexGrow: 1, minWidth: 0 }}>
            <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap">
              <Typography variant="subtitle1" fontWeight={700}>
                {professional.displayName}
              </Typography>
              {professional.verified ? (
                <Chip
                  size="small"
                  color="success"
                  variant="outlined"
                  icon={<VerifiedRoundedIcon sx={{ fontSize: 14 }} />}
                  label="Verified"
                  sx={{ bgcolor: brand.greenSoft, borderColor: 'transparent' }}
                />
              ) : null}
            </Stack>

            <Stack direction="row" spacing={1} alignItems="center" sx={{ mt: 0.25 }}>
              <Stack direction="row" spacing={0.25} alignItems="center">
                <StarRounded sx={{ fontSize: 17, color: brand.warm }} aria-hidden />
                <Typography variant="body2" fontWeight={700}>
                  {professional.rating.toFixed(1)}
                </Typography>
              </Stack>
              <Stack direction="row" spacing={0.5} alignItems="center">
                <WorkHistoryRoundedIcon
                  sx={{ fontSize: 16, color: 'text.secondary' }}
                  aria-hidden
                />
                <Typography variant="body2" color="text.secondary">
                  {professional.jobsCompleted} jobs
                </Typography>
              </Stack>
            </Stack>

            <Stack direction="row" spacing={1.5} alignItems="center" sx={{ mt: 0.75 }}>
              <MetaItem
                icon={<PlaceRoundedIcon sx={{ fontSize: 16 }} />}
                label={formatDistanceKm(professional.distanceKm)}
              />
              <MetaItem
                icon={<AccessTimeRoundedIcon sx={{ fontSize: 16 }} />}
                label={`ETA ${formatEtaMinutes(professional.etaMinutes)}`}
              />
            </Stack>
          </Box>
        </Stack>

        <Divider sx={{ my: 1.5 }} />

        <Box>
          <Typography variant="caption" color="text.secondary" display="block">
            Starting price
          </Typography>
          <Typography variant="h6" component="p" fontWeight={800}>
            {formatCurrency(professional.startingPrice, professional.currency)}
          </Typography>
        </Box>
      </CardContent>
    </Card>
  );
}

/** Small icon + label pair used for the distance and ETA facts. */
function MetaItem({ icon, label }: { icon: React.ReactNode; label: string }) {
  return (
    <Stack
      direction="row"
      spacing={0.5}
      alignItems="center"
      sx={{
        color: 'text.secondary',
        bgcolor: brand.slateSoft,
        px: 1,
        py: 0.25,
        borderRadius: `${radius.sm}px`,
      }}
    >
      {icon}
      <Typography variant="caption" fontWeight={600}>
        {label}
      </Typography>
    </Stack>
  );
}
