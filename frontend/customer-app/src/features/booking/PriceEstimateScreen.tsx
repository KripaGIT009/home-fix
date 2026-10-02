import { useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Divider,
  Skeleton,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import BoltRoundedIcon from '@mui/icons-material/BoltRounded';
import LocalOfferRoundedIcon from '@mui/icons-material/LocalOfferRounded';
import ReceiptLongRoundedIcon from '@mui/icons-material/ReceiptLongRounded';
import ShieldRoundedIcon from '@mui/icons-material/ShieldRounded';
import HourglassEmptyRoundedIcon from '@mui/icons-material/HourglassEmptyRounded';
import { AppShell } from '@components/AppShell';
import { EmptyState, IconTile, InlineError } from '@components/StateViews';
import { isApiError, isUnreachableError } from '@api/client';
import { formatCurrency } from '@lib/format';
import { brand, radius } from '@lib/theme';
import type { EstimateRequestPayload, PriceEstimate } from './api';
import { useCreateBooking, useEstimate } from './hooks';
import { useBookingDraftStore } from './draftStore';
import { ADDRESS_LOCATION_REQUIRED_MESSAGE, couponSchema, type CouponFormValues } from './schemas';

/**
 * Price Estimate screen (Requirements 6 and 7.3). Requests an itemized estimate
 * for the drafted request, renders every price component, lets the customer
 * apply/validate a coupon, and provides confirm/cancel actions. Confirming
 * creates the booking and, for a scheduled one, confirms it so it is
 * dispatched (Requirement 7.5); a Pricing Engine outage (503) blocks
 * confirmation (Requirement 7.4), as does an address without coordinates.
 */
export function PriceEstimateScreen() {
  const { subcategoryId = '' } = useParams();
  const navigate = useNavigate();

  const draft = useBookingDraftStore((state) => state.draft);
  const clearDraft = useBookingDraftStore((state) => state.clearDraft);

  const estimate = useEstimate();
  const createBooking = useCreateBooking();

  // Track the coupon that produced the current estimate so we can send it on
  // to booking creation and reflect it in the UI.
  const [appliedCouponCode, setAppliedCouponCode] = useState<string | undefined>(undefined);

  const estimateMutate = estimate.mutate;
  const estimateReset = estimate.reset;

  const runEstimate = useCallback(
    (couponCode?: string) => {
      if (!draft) return;
      const payload: EstimateRequestPayload = {
        subcategoryId: draft.subcategoryId,
        isEmergency: draft.isEmergency,
        ...(draft.scheduledAt ? { scheduledAt: draft.scheduledAt } : {}),
        address: draft.address,
        ...(couponCode ? { couponCode } : {}),
      };
      estimateReset();
      estimateMutate(payload, {
        onSuccess: () => setAppliedCouponCode(couponCode),
      });
    },
    [draft, estimateMutate, estimateReset],
  );

  // Request the base estimate once when the screen mounts with a valid draft.
  useEffect(() => {
    if (draft) {
      runEstimate(undefined);
    }
    // Intentionally run once for the initial draft.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<CouponFormValues>({
    resolver: zodResolver(couponSchema),
    defaultValues: { code: '' },
    mode: 'onSubmit',
  });

  const handleApplyCoupon = useCallback(
    ({ code }: CouponFormValues) => {
      runEstimate(code.trim().toUpperCase());
    },
    [runEstimate],
  );

  const handleConfirm = useCallback(() => {
    if (!draft || !estimate.data) return;
    createBooking.mutate(
      {
        categoryId: draft.categoryId,
        subcategoryId: draft.subcategoryId,
        isEmergency: draft.isEmergency,
        ...(draft.scheduledAt ? { scheduledAt: draft.scheduledAt } : {}),
        address: draft.address,
        ...(draft.description ? { description: draft.description } : {}),
        ...(appliedCouponCode ? { couponCode: appliedCouponCode } : {}),
        media: draft.media,
      },
      {
        onSuccess: (response) => {
          clearDraft();
          // Dispatch offers the job to providers itself, emergency or scheduled;
          // there is no step where the customer picks one. Tracking shows the
          // search until a provider accepts.
          navigate(`/bookings/${response.bookingId}/track`, { replace: true });
        },
      },
    );
  }, [appliedCouponCode, clearDraft, createBooking, draft, estimate.data, navigate]);

  const handleCancel = useCallback(() => {
    clearDraft();
    navigate(`/book/${subcategoryId}`);
  }, [clearDraft, navigate, subcategoryId]);

  // No draft in memory (e.g. after a hard refresh) — send the user back.
  if (!draft) {
    return (
      <AppShell title="Price estimate">
        <EmptyState
          icon={<HourglassEmptyRoundedIcon />}
          title="Your request has expired"
          description="We don't keep unfinished requests. Start again — it only takes a minute."
          action={
            <Button variant="contained" size="large" onClick={() => navigate('/home')}>
              Back to home
            </Button>
          }
        />
      </AppShell>
    );
  }

  // The request form requires coordinates; this guards a draft that reached
  // here without them, since the booking could never be dispatched.
  const hasLocation = draft.address.latitude !== undefined && draft.address.longitude !== undefined;

  const couponError =
    estimate.isError && isApiError(estimate.error) ? estimate.error.message : null;
  const bookingError =
    createBooking.isError && isApiError(createBooking.error) ? createBooking.error.message : null;

  return (
    <AppShell title="Review & confirm" width="full">
      {estimate.isPending ? (
        <Box
          aria-busy="true"
          aria-label="Calculating your estimate"
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: '1fr', md: 'minmax(0, 1fr) 360px' },
            gap: 3,
          }}
        >
          <Skeleton variant="rounded" height={320} sx={{ borderRadius: `${radius.lg}px` }} />
          <Skeleton variant="rounded" height={220} sx={{ borderRadius: `${radius.lg}px` }} />
        </Box>
      ) : estimate.isError ? (
        <InlineError
          error={estimate.error}
          title={
            isUnreachableError(estimate.error)
              ? "We can't price this job right now"
              : "We couldn't update your estimate"
          }
          onRetry={() => runEstimate(appliedCouponCode)}
        />
      ) : estimate.data ? (
        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: '1fr', md: 'minmax(0, 1fr) 360px' },
            gridTemplateAreas: {
              xs: '"total" "details" "actions"',
              md: '"details total" "details actions"',
            },
            gap: { xs: 2, md: 3 },
            alignItems: 'start',
          }}
        >
          <Stack spacing={2} sx={{ gridArea: 'details', minWidth: 0 }}>
            <Card>
              <CardContent>
                <Stack direction="row" spacing={1.5} alignItems="center" sx={{ mb: 2.5 }}>
                  <IconTile size={36}>
                    <ReceiptLongRoundedIcon />
                  </IconTile>
                  <Typography variant="h6" component="h2">
                    Price breakdown
                  </Typography>
                </Stack>
                <EstimateBreakdown estimate={estimate.data} />
              </CardContent>
            </Card>

            <Card>
              <CardContent>
                <Stack
                  component="form"
                  spacing={1.5}
                  onSubmit={(event) => void handleSubmit(handleApplyCoupon)(event)}
                  noValidate
                >
                  <Stack direction="row" spacing={1.5} alignItems="center">
                    <IconTile size={36} bg={brand.warmSoft} color={brand.warmDark}>
                      <LocalOfferRoundedIcon />
                    </IconTile>
                    <Typography variant="h6" component="h2">
                      Have a coupon?
                    </Typography>
                  </Stack>
                  <Stack direction="row" spacing={1} alignItems="flex-start">
                    <TextField
                      placeholder="Enter code"
                      fullWidth
                      error={Boolean(errors.code) || Boolean(couponError)}
                      helperText={errors.code?.message ?? couponError ?? ' '}
                      inputProps={{
                        'aria-label': 'Coupon code',
                        style: { textTransform: 'uppercase' },
                      }}
                      {...register('code')}
                    />
                    <Button
                      type="submit"
                      variant="outlined"
                      size="large"
                      disabled={estimate.isPending}
                      sx={{ flexShrink: 0 }}
                    >
                      Apply
                    </Button>
                  </Stack>
                  {estimate.data.appliedCoupon ? (
                    <Alert severity="success">
                      Coupon {estimate.data.appliedCoupon.code} applied — you saved{' '}
                      {formatCurrency(
                        Math.abs(estimate.data.appliedCoupon.discountAmount),
                        estimate.data.currency,
                      )}
                      .
                    </Alert>
                  ) : null}
                </Stack>
              </CardContent>
            </Card>
          </Stack>

          <TotalCard
            total={estimate.data.total}
            currency={estimate.data.currency}
            isEmergency={draft.isEmergency}
          />

          <Card sx={{ gridArea: 'actions', position: { md: 'sticky' }, top: { md: 96 } }}>
            <CardContent>
              <Stack spacing={1.5}>
                <Stack direction="row" spacing={1} alignItems="flex-start">
                  <ShieldRoundedIcon sx={{ fontSize: 20, color: 'success.main', mt: 0.25 }} />
                  <Typography variant="body2" color="text.secondary">
                    The final price may change with the actual work and parts. You approve any
                    change before it is charged.
                  </Typography>
                </Stack>

                {!hasLocation ? (
                  <Alert
                    severity="warning"
                    action={
                      <Button color="inherit" size="small" onClick={handleCancel}>
                        Edit address
                      </Button>
                    }
                  >
                    {ADDRESS_LOCATION_REQUIRED_MESSAGE}
                  </Alert>
                ) : null}

                {bookingError ? (
                  <Alert
                    severity={isUnreachableError(createBooking.error) ? 'warning' : 'error'}
                    role="alert"
                  >
                    {bookingError}
                  </Alert>
                ) : null}

                <Button
                  variant="contained"
                  size="large"
                  color={draft.isEmergency ? 'error' : 'primary'}
                  fullWidth
                  onClick={handleConfirm}
                  disabled={createBooking.isPending || !hasLocation}
                  {...(draft.isEmergency && !createBooking.isPending
                    ? { startIcon: <BoltRoundedIcon /> }
                    : {})}
                >
                  {createBooking.isPending
                    ? 'Confirming…'
                    : draft.isEmergency
                      ? 'Confirm & dispatch now'
                      : 'Confirm booking'}
                </Button>
                <Button
                  variant="text"
                  size="large"
                  fullWidth
                  onClick={handleCancel}
                  disabled={createBooking.isPending}
                >
                  Cancel
                </Button>
              </Stack>
            </CardContent>
          </Card>
        </Box>
      ) : null}
    </AppShell>
  );
}

/** Headline card carrying the number the customer actually decides on. */
function TotalCard({
  total,
  currency,
  isEmergency,
}: {
  total: number;
  currency: string;
  isEmergency: boolean;
}) {
  return (
    <Card
      sx={{
        gridArea: 'total',
        borderColor: 'transparent',
        background: `linear-gradient(150deg, ${brand.accent} 0%, ${brand.accentDark} 60%, ${brand.accentDeep} 100%)`,
        color: 'common.white',
      }}
    >
      <CardContent>
        <Stack direction="row" alignItems="flex-start" justifyContent="space-between">
          <Typography variant="body2" sx={{ opacity: 0.85 }}>
            Estimated total
          </Typography>
          {isEmergency ? (
            <Stack
              direction="row"
              spacing={0.5}
              alignItems="center"
              sx={{
                px: 1,
                py: 0.25,
                borderRadius: `${radius.pill}px`,
                bgcolor: 'rgba(255,255,255,0.16)',
              }}
            >
              <BoltRoundedIcon sx={{ fontSize: 15 }} aria-hidden />
              <Typography variant="caption" fontWeight={700}>
                Emergency
              </Typography>
            </Stack>
          ) : null}
        </Stack>
        <Typography
          variant="h1"
          component="p"
          sx={{ mt: 0.5, fontSize: { xs: '2.25rem', md: '2.75rem' } }}
        >
          {formatCurrency(total, currency)}
        </Typography>
        <Typography variant="caption" sx={{ opacity: 0.8 }}>
          Including taxes and fees. Pay after the job is done.
        </Typography>
      </CardContent>
    </Card>
  );
}

/** Renders the itemized breakdown and total (Requirement 6.9). */
function EstimateBreakdown({ estimate }: { estimate: PriceEstimate }) {
  return (
    <Stack spacing={1.25}>
      {estimate.lineItems.map((item) => (
        <Stack key={item.key} direction="row" justifyContent="space-between" spacing={2}>
          <Typography variant="body1" color="text.secondary">
            {item.label}
          </Typography>
          <Typography
            variant="body1"
            fontWeight={600}
            color={item.amount < 0 ? 'success.main' : 'text.primary'}
          >
            {item.amount < 0 ? '−' : ''}
            {formatCurrency(Math.abs(item.amount), estimate.currency)}
          </Typography>
        </Stack>
      ))}
      <Divider sx={{ my: 0.5 }} />
      <Stack direction="row" justifyContent="space-between" alignItems="center">
        <Typography variant="h6" component="p">
          Total
        </Typography>
        <Typography variant="h6" component="p">
          {formatCurrency(estimate.total, estimate.currency)}
        </Typography>
      </Stack>
    </Stack>
  );
}
