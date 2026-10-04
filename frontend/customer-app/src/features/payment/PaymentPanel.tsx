import type { ReactNode } from 'react';
import { Alert, Button, Chip, CircularProgress, Stack, Typography } from '@mui/material';
import PaymentsRoundedIcon from '@mui/icons-material/PaymentsRounded';
import TaskAltRoundedIcon from '@mui/icons-material/TaskAltRounded';
import { friendlyErrorMessage, isApiError } from '@api/client';
import { formatCurrency } from '@lib/format';
import type { BookingDetail } from '@features/history/api';
import { StatePanel } from '@features/tracking/StatePanel';
import { PAYMENT_METHODS } from './api';
import { usePaymentFlow, type PaymentFlow } from './hooks';

/**
 * Paying for a completed job (Requirement 12). Live tracking shows it as a
 * full state panel ({@link PaymentPanel}); the booking detail screen puts the
 * same controls in its summary card ({@link PaymentCardActions}). Both run the
 * same flow: pick a method, pay the booking's amount, follow the booking until
 * it is PAYMENT_COMPLETED, and offer another try when a payment is declined.
 */

/** The tracking screen's payment state: pay, confirming, or paid. */
export function PaymentPanel({
  detail,
  paidActions,
}: {
  detail: BookingDetail;
  /** Follow-up actions for the paid state (e.g. view invoice, book again). */
  paidActions?: ReactNode;
}) {
  const flow = usePaymentFlow(detail);
  const amount = formatCurrency(detail.amount, detail.currency);

  if (detail.status === 'PAYMENT_COMPLETED') {
    return (
      <StatePanel
        icon={<TaskAltRoundedIcon />}
        tone="success"
        title={`Paid ${amount}`}
        actions={paidActions}
      >
        Thank you — your payment went through and this booking is complete.
      </StatePanel>
    );
  }
  if (!flow.payable) return null;

  if (flow.confirming) {
    return (
      <StatePanel
        icon={<PaymentsRoundedIcon />}
        title={flow.settled ? 'Payment received' : 'Confirming your payment'}
      >
        <ConfirmingNote flow={flow} amount={amount} />
      </StatePanel>
    );
  }

  return (
    <StatePanel
      icon={<PaymentsRoundedIcon />}
      tone="warm"
      title={`Pay ${amount}`}
      extra={<MethodPicker flow={flow} id={`pay-method-${detail.bookingId}`} />}
      actions={<PayButton flow={flow} amount={amount} />}
    >
      <Stack spacing={1}>
        <span>The job is done. Pay to finish up your booking.</span>
        <PartsList detail={detail} />
        <PaymentNotices flow={flow} />
      </Stack>
    </StatePanel>
  );
}

/**
 * The booking detail card's pay controls: method, outcome and the primary Pay
 * button. Renders nothing unless the booking is payable.
 */
export function PaymentCardActions({ detail }: { detail: BookingDetail }) {
  const flow = usePaymentFlow(detail);
  if (!flow.payable) return null;
  const amount = formatCurrency(detail.amount, detail.currency);

  if (flow.confirming) {
    return (
      <Typography variant="body2" color="text.secondary" component="div" role="status">
        <ConfirmingNote flow={flow} amount={amount} />
      </Typography>
    );
  }
  return (
    <Stack spacing={1.5}>
      <MethodPicker flow={flow} id={`pay-method-card-${detail.bookingId}`} />
      <PaymentNotices flow={flow} />
      <PayButton flow={flow} amount={amount} fullWidth />
    </Stack>
  );
}

/** The parts the professional added, so the customer can see what the total is made of. */
export function PartsList({ detail }: { detail: BookingDetail }) {
  const parts = detail.parts ?? [];
  if (parts.length === 0) return null;
  return (
    <>
      {parts.map((part) => (
        <span key={part.id}>
          {part.itemName} × {part.quantity} —{' '}
          {formatCurrency(part.quantity * part.unitCost, detail.currency)}
        </span>
      ))}
    </>
  );
}

function MethodPicker({ flow, id }: { flow: PaymentFlow; id: string }) {
  return (
    <Stack spacing={1}>
      <Typography id={id} variant="subtitle2" component="p">
        Pay with
      </Typography>
      <Stack
        role="radiogroup"
        aria-labelledby={id}
        direction="row"
        spacing={1}
        useFlexGap
        flexWrap="wrap"
      >
        {PAYMENT_METHODS.map((option) => {
          const selected = flow.method === option.value;
          return (
            <Chip
              key={option.value}
              role="radio"
              aria-checked={selected}
              label={option.label}
              disabled={flow.submitting}
              onClick={() => flow.setMethod(option.value)}
              color={selected ? 'primary' : 'default'}
              variant={selected ? 'filled' : 'outlined'}
            />
          );
        })}
      </Stack>
    </Stack>
  );
}

function PayButton({
  flow,
  amount,
  fullWidth = false,
}: {
  flow: PaymentFlow;
  amount: string;
  fullWidth?: boolean;
}) {
  return (
    <Button
      variant="contained"
      size="large"
      fullWidth={fullWidth}
      startIcon={<PaymentsRoundedIcon />}
      disabled={flow.submitting}
      onClick={flow.submit}
    >
      {flow.submitting ? 'Paying…' : `Pay ${amount}`}
    </Button>
  );
}

function PaymentNotices({ flow }: { flow: PaymentFlow }) {
  return (
    <>
      {flow.declined ? (
        <Alert severity="error">
          Your payment didn&apos;t go through. Please try again, or choose another method.
        </Alert>
      ) : null}
      {flow.cancelled ? (
        flow.cancelled.failure ? (
          <Alert severity="error">
            Your payment didn&apos;t go through: {flow.cancelled.failure}. You haven&apos;t been
            charged. Please try again, or choose another method.
          </Alert>
        ) : (
          <Alert severity="info">Payment cancelled. You haven&apos;t been charged.</Alert>
        )
      ) : null}
      {flow.error ? (
        <Alert severity="error">
          {isApiError(flow.error)
            ? friendlyErrorMessage(flow.error)
            : flow.error.message || friendlyErrorMessage(flow.error)}
        </Alert>
      ) : null}
    </>
  );
}

function ConfirmingNote({ flow, amount }: { flow: PaymentFlow; amount: string }) {
  return (
    <Stack
      direction="row"
      spacing={1}
      alignItems="center"
      justifyContent={{ xs: 'center', sm: 'flex-start' }}
    >
      <CircularProgress size={14} aria-hidden />
      <span>
        {flow.settled
          ? `We've received your ${amount}. Finishing up your booking…`
          : "We're waiting for your bank to confirm the payment. This updates on its own."}
      </span>
    </Stack>
  );
}
