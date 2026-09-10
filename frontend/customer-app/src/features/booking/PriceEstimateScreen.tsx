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
  Chip,
  CircularProgress,
  Divider,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import BoltRoundedIcon from '@mui/icons-material/BoltRounded';
import LocalOfferRoundedIcon from '@mui/icons-material/LocalOfferRounded';
import ReceiptLongRoundedIcon from '@mui/icons-material/ReceiptLongRounded';
import ShieldRoundedIcon from '@mui/icons-material/ShieldRounded';
import { AppShell } from '@components/AppShell';
import { isApiError } from '@api/client';
import { formatCurrency } from '@lib/format';
import type { EstimateRequestPayload, PriceEstimate } from './api';
import { useCreateBooking, useEstimate } from './hooks';
import { useBookingDraftStore } from './draftStore';
import { couponSchema, type CouponFormValues } from './schemas';

/**
 * Price Estimate screen (Requirements 6 and 7.3). Requests an itemized estimate
 * for the drafted request, renders every price component, lets the customer
 * apply/validate a coupon, and provides confirm/cancel actions. Confirming
 * creates the booking (Requirement 7.5); a Pricing Engine outage (503) blocks
 * confirmation (Requirement 7.4).
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
        subcategoryId: draft.subcategoryId,
        isEmergency: draft.isEmergency,
        ...(draft.scheduledAt ? { scheduledAt: draft.scheduledAt } : {}),
        address: draft.address,
        ...(draft.description ? { description: draft.description } : {}),
        estimateId: estimate.data.estimateId,
        ...(appliedCouponCode ? { couponCode: appliedCouponCode } : {}),
        media: draft.media,
      },
      {
        onSuccess: (response) => {
          clearDraft();
          // Emergency bookings go straight to tracking; scheduled bookings show
          // the available professionals list first.
          const target = response.isEmergency
            ? `/bookings/${response.bookingId}/track`
            : `/book/${response.bookingId}/professionals`;
          navigate(target, { replace: true });
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
        <Stack spacing={2} alignItems="flex-start">
          <Alert severity="info" sx={{ width: '100%' }}>
            Your request has expired. Please start again.
          </Alert>
          <Button variant="contained" onClick={() => navigate('/home')}>
            Back to Home
          </Button>
        </Stack>
      </AppShell>
    );
  }

  const couponError =
    estimate.isError && isApiError(estimate.error) ? estimate.error.message : null;
  const bookingError =
    createBooking.isError && isApiError(createBooking.error) ? createBooking.error.message : null;

  return (
    <AppShell title="Price estimate">
      <Stack spacing={2}>
        {estimate.isPending ? (
          <Box sx={{ display: 'flex', justifyContent: 'center', py: 8 }}>
            <CircularProgress aria-label="Calculating estimate" />
          </Box>
        ) : estimate.isError ? (
          <Stack spacing={2} sx={{ py: 2 }}>
            <Alert severity="error">
              {isApiError(estimate.error)
                ? estimate.error.message
                : 'Could not calculate an estimate. Please try again.'}
            </Alert>
            <Button variant="outlined" onClick={() => runEstimate(appliedCouponCode)}>
              Try again
            </Button>
          </Stack>
        ) : estimate.data ? (
          <>
            <TotalCard
              total={estimate.data.total}
              currency={estimate.data.currency}
              isEmergency={draft.isEmergency}
            />

            <Card>
              <CardContent>
                <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1.5 }}>
                  <ReceiptLongRoundedIcon sx={{ color: 'primary.main' }} />
                  <Typography variant="subtitle1" fontWeight={700}>
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
                  <Stack direction="row" spacing={1} alignItems="center">
                    <LocalOfferRoundedIcon sx={{ color: 'primary.main' }} />
                    <Typography variant="subtitle1" fontWeight={700}>
                      Have a coupon?
                    </Typography>
                  </Stack>
                  <Stack direction="row" spacing={1} alignItems="flex-start">
                    <TextField
                      placeholder="Enter code"
                      size="small"
                      fullWidth
                      error={Boolean(errors.code) || Boolean(couponError)}
                      helperText={errors.code?.message ?? couponError ?? ' '}
                      inputProps={{
                        'aria-label': 'Coupon code',
                        style: { textTransform: 'uppercase' },
                      }}
                      {...register('code')}
                    />
                    <Button type="submit" variant="outlined" disabled={estimate.isPending}>
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

            <Stack direction="row" spacing={1} alignItems="center" sx={{ px: 0.5 }}>
              <ShieldRoundedIcon sx={{ fontSize: 18, color: 'success.main' }} />
              <Typography variant="caption" color="text.secondary">
                Final price may vary with actual work and materials used. You approve any change
                before it is charged.
              </Typography>
            </Stack>

            {bookingError ? <Alert severity="error">{bookingError}</Alert> : null}

            <Stack spacing={1}>
              <Button
                variant="contained"
                size="large"
                fullWidth
                onClick={handleConfirm}
                disabled={createBooking.isPending}
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
          </>
        ) : null}
      </Stack>
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
        borderColor: 'transparent',
        background: 'linear-gradient(135deg, #2563EB 0%, #1D4ED8 100%)',
        color: 'common.white',
      }}
    >
      <CardContent sx={{ py: 2.5 }}>
        <Stack direction="row" alignItems="center" justifyContent="space-between">
          <Box>
            <Typography variant="body2" sx={{ opacity: 0.85 }}>
              Estimated total
            </Typography>
            <Typography variant="h3" component="p" fontWeight={800} sx={{ mt: 0.25 }}>
              {formatCurrency(total, currency)}
            </Typography>
          </Box>
          {isEmergency ? (
            <Chip
              icon={<BoltRoundedIcon sx={{ fontSize: 16 }} />}
              label="Emergency"
              sx={{ bgcolor: 'rgba(255,255,255,0.2)', color: 'common.white' }}
            />
          ) : null}
        </Stack>
      </CardContent>
    </Card>
  );
}

/** Renders the itemized breakdown and total (Requirement 6.9). */
function EstimateBreakdown({ estimate }: { estimate: PriceEstimate }) {
  return (
    <Stack spacing={1}>
      {estimate.lineItems.map((item) => (
        <Stack key={item.key} direction="row" justifyContent="space-between">
          <Typography variant="body2" color="text.secondary">
            {item.label}
          </Typography>
          <Typography
            variant="body2"
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
        <Typography variant="subtitle1" fontWeight={700}>
          Total
        </Typography>
        <Typography variant="subtitle1" fontWeight={700}>
          {formatCurrency(estimate.total, estimate.currency)}
        </Typography>
      </Stack>
    </Stack>
  );
}
