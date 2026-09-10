import { useCallback, useMemo, useState } from 'react';
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
import { isApiError } from '@api/client';
import { formatCurrency, formatDistanceKm, formatEtaMinutes } from '@lib/format';
import { brand } from '@lib/theme';
import type { AvailableProfessional } from './api';
import { useAvailableProfessionals, useSelectProfessional } from './hooks';

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
 * Selecting a Provider assigns them and moves the customer to live tracking.
 *
 * The list arrives ranked by dispatch fit; the sort chips let the customer
 * re-rank by the one attribute they care about without another round trip.
 */
export function AvailableProfessionalsScreen() {
  const { bookingId = '' } = useParams();
  const navigate = useNavigate();

  const query = useAvailableProfessionals(bookingId);
  const select = useSelectProfessional(bookingId);
  const [sortKey, setSortKey] = useState<SortKey>('nearby');

  const professionals = useMemo(() => {
    if (!query.data) return undefined;
    return [...query.data].sort(COMPARATORS[sortKey]);
  }, [query.data, sortKey]);

  const selectMutate = select.mutate;
  const handleSelect = useCallback(
    (providerId: string) => {
      selectMutate(providerId, {
        onSuccess: () => navigate(`/bookings/${bookingId}/track`),
      });
    },
    [bookingId, navigate, selectMutate],
  );

  const selectError = select.isError && isApiError(select.error) ? select.error.message : null;

  return (
    <AppShell title="Professionals near you">
      <Stack spacing={2}>
        <Typography variant="body2" color="text.secondary">
          Verified pros near you, ranked by fit. Pick one to get started.
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

        {selectError ? <Alert severity="error">{selectError}</Alert> : null}

        <QueryStateView
          isLoading={query.isLoading}
          isError={query.isError}
          error={query.error}
          onRetry={() => void query.refetch()}
          isEmpty={!professionals || professionals.length === 0}
          emptyMessage="No professionals are available right now. We'll keep searching."
        >
          <Stack spacing={1.5}>
            {professionals?.map((professional) => (
              <ProfessionalCard
                key={professional.providerId}
                professional={professional}
                onSelect={() => handleSelect(professional.providerId)}
                disabled={select.isPending}
              />
            ))}
          </Stack>
        </QueryStateView>
      </Stack>
    </AppShell>
  );
}

function ProfessionalCard({
  professional,
  onSelect,
  disabled,
}: {
  professional: AvailableProfessional;
  onSelect: () => void;
  disabled: boolean;
}) {
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
            <Avatar aria-hidden sx={{ width: 52, height: 52, bgcolor: 'primary.main' }}>
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
                <StarRounded sx={{ fontSize: 17, color: 'warning.main' }} aria-hidden />
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

        <Stack direction="row" justifyContent="space-between" alignItems="center">
          <Box>
            <Typography variant="caption" color="text.secondary" display="block">
              Starting price
            </Typography>
            <Typography variant="subtitle1" fontWeight={700}>
              {formatCurrency(professional.startingPrice, professional.currency)}
            </Typography>
          </Box>
          <Button
            variant="contained"
            onClick={onSelect}
            disabled={disabled}
            aria-label={`Choose ${professional.displayName}`}
          >
            Book now
          </Button>
        </Stack>
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
      sx={{ color: 'text.secondary', bgcolor: brand.canvas, px: 1, py: 0.25, borderRadius: 1.5 }}
    >
      {icon}
      <Typography variant="caption" fontWeight={600}>
        {label}
      </Typography>
    </Stack>
  );
}
