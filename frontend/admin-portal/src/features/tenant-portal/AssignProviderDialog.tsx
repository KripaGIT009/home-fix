import { useMemo, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  Link,
  Radio,
  RadioGroup,
  Stack,
  Typography,
  useMediaQuery,
  type Theme,
} from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';
import { isApiError } from '@api/client';
import { brand } from '@lib/theme';
import { formatCurrency, shortId } from '@lib/format';
import {
  hasErrorCode,
  notAssignableReason,
  tenantErrorMessage,
  type TeamProvider,
} from '@features/tenants/model';
import { AvailabilityChip, RatingCell } from '@features/tenants/TeamChips';
import type { TenantBooking } from './api';
import { useAssignBooking, useTeam } from './hooks';
import { ServiceAddress } from './ServiceAddress';

interface AssignProviderDialogProps {
  booking: TenantBooking;
  onClose: () => void;
  /** The assignment went through; the name is for the confirmation. */
  onAssigned: (providerName: string) => void;
  /** The request left the queue under us (lost race); the message says why. */
  onLost: (message: string) => void;
}

/**
 * Order the team for choosing: assignable and available now first, then
 * assignable, then everyone who cannot take the job (shown, but disabled, so
 * the admin sees why a familiar name cannot be picked).
 */
function rank(provider: TeamProvider): number {
  if (provider.assignable && provider.availableNow) return 0;
  if (provider.assignable) return 1;
  return 2;
}

/**
 * Assign a queued request to one of the Tenant's Providers (Requirements
 * MT-5.2, MT-11.3). Lists the team with availability now and assignability
 * (Requirement MT-8.4); only assignable Providers can be chosen.
 *
 * Two 409s are expected and handled differently:
 * - BOOKING_NOT_ASSIGNABLE (or a 404): another partner won the race or the
 *   request moved on. Nothing in this dialog can fix that, so it closes and
 *   the Requests screen explains while the queue refreshes (Requirement MT-5.4).
 * - PROVIDER_NOT_ASSIGNABLE: the chosen Provider stopped being assignable since
 *   the list loaded. The request is still ours to assign, so the dialog stays
 *   open with a refreshed team and asks for someone else (Requirement MT-5.5).
 */
export function AssignProviderDialog({
  booking,
  onClose,
  onAssigned,
  onLost,
}: AssignProviderDialogProps) {
  // Phones get the whole screen: a dialog this dense is unusable as a 390 px card.
  const phone = useMediaQuery((theme: Theme) => theme.breakpoints.down('sm'));
  const teamQuery = useTeam({ poll: true });
  const assign = useAssignBooking();
  const [selected, setSelected] = useState<string>('');

  const team = useMemo(
    () => [...(teamQuery.data ?? [])].sort((a, b) => rank(a) - rank(b)),
    [teamQuery.data],
  );
  const assignableCount = team.filter((p) => p.assignable).length;
  const chosen = team.find((p) => p.providerId === selected);
  // A Provider who stopped being assignable on a refresh cannot stay selected.
  const canSubmit = Boolean(chosen?.assignable) && !assign.isPending;

  const handleAssign = () => {
    if (!chosen) return;
    assign.mutate(
      { bookingId: booking.id, providerId: chosen.providerId },
      {
        onSuccess: () => onAssigned(chosen.displayName ?? 'the provider'),
        onError: (error) => {
          if (
            hasErrorCode(error, 'BOOKING_NOT_ASSIGNABLE') ||
            (isApiError(error) && error.status === 404)
          ) {
            onLost(
              `${booking.reference} was just assigned by another partner or has moved on, so it has left your queue. The queue has been refreshed.`,
            );
            return;
          }
          if (hasErrorCode(error, 'PROVIDER_NOT_ASSIGNABLE')) {
            setSelected('');
            void teamQuery.refetch();
          }
        },
      },
    );
  };

  return (
    <Dialog
      open
      onClose={assign.isPending ? undefined : onClose}
      maxWidth="sm"
      fullWidth
      fullScreen={phone}
    >
      <DialogTitle>
        Assign {booking.reference}
        <Typography variant="body2" color="text.secondary">
          The provider confirms or declines in their app. A decline returns the request to your
          queue.
        </Typography>
      </DialogTitle>
      <DialogContent dividers>
        <Box
          sx={{
            p: 1.5,
            mb: 2,
            borderRadius: 2,
            bgcolor: brand.canvas,
            border: `1px solid ${brand.line}`,
          }}
        >
          <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 0.5 }}>
            <Typography variant="subtitle1">{booking.serviceName ?? 'Service'}</Typography>
            {booking.isEmergency ? <Chip size="small" color="error" label="Emergency" /> : null}
            <Box sx={{ flexGrow: 1 }} />
            <Typography variant="subtitle1">
              {formatCurrency(booking.amount, booking.currency)}
            </Typography>
          </Stack>
          <ServiceAddress booking={booking} />
        </Box>

        <Typography variant="subtitle2" sx={{ mb: 1 }}>
          Choose a provider ({assignableCount} of {team.length} can be assigned)
        </Typography>

        {teamQuery.isLoading ? (
          <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
            <CircularProgress size={28} aria-label="Loading your team" />
          </Box>
        ) : teamQuery.isError ? (
          <Alert severity="error">{tenantErrorMessage(teamQuery.error)}</Alert>
        ) : assignableCount === 0 ? (
          <Alert severity="info">
            Nobody in your team can be assigned right now: providers must be verified by HomeFix
            first.{' '}
            <Link component={RouterLink} to="/tenant/team">
              Review your team
            </Link>
          </Alert>
        ) : null}

        {team.length > 0 ? (
          <RadioGroup
            aria-label="Provider"
            value={selected}
            onChange={(event) => setSelected(event.target.value)}
            sx={{ gap: 1 }}
          >
            {team.map((provider) => (
              <FormControlLabel
                key={provider.providerId}
                value={provider.providerId}
                disabled={!provider.assignable}
                control={<Radio size="small" />}
                sx={{
                  m: 0,
                  px: 1,
                  py: 0.75,
                  borderRadius: 2,
                  border: `1px solid ${selected === provider.providerId ? brand.accent : brand.line}`,
                  bgcolor: selected === provider.providerId ? brand.accentSoft : 'transparent',
                  alignItems: 'flex-start',
                  '& .MuiFormControlLabel-label': { flexGrow: 1, minWidth: 0 },
                }}
                label={<ProviderOption provider={provider} />}
              />
            ))}
          </RadioGroup>
        ) : null}

        {assign.isError && !hasErrorCode(assign.error, 'BOOKING_NOT_ASSIGNABLE') ? (
          <Alert severity="error" sx={{ mt: 2 }}>
            {tenantErrorMessage(assign.error)}
          </Alert>
        ) : null}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={assign.isPending}>
          Cancel
        </Button>
        <Button variant="contained" onClick={handleAssign} disabled={!canSubmit}>
          {assign.isPending
            ? 'Assigning…'
            : chosen
              ? `Assign ${chosen.displayName ?? 'provider'}`
              : 'Assign'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

function ProviderOption({ provider }: { provider: TeamProvider }) {
  return (
    <Stack
      direction={{ xs: 'column', sm: 'row' }}
      spacing={{ xs: 0.5, sm: 1.5 }}
      alignItems={{ xs: 'flex-start', sm: 'center' }}
      sx={{ py: 0.25 }}
    >
      <Box sx={{ flexGrow: 1, minWidth: 0 }}>
        <Typography variant="body2" fontWeight={600} noWrap>
          {provider.displayName ?? shortId(provider.providerId)}
        </Typography>
        <Typography variant="caption" color="text.secondary" component="div">
          {provider.assignable
            ? (provider.primarySkill ?? 'No primary skill set')
            : notAssignableReason(provider)}
        </Typography>
      </Box>
      {provider.rating == null ? null : <RatingCell rating={provider.rating} />}
      <AvailabilityChip availableNow={provider.availableNow} />
    </Stack>
  );
}
