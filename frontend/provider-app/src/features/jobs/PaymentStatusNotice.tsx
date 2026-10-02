import { Alert, AlertTitle } from '@mui/material';
import type { BookingStatus } from '@lib/bookingStatus';
import { isAwaitingPayment } from './status';

/**
 * Where the customer's payment stands once the job is done (Requirement 12):
 * waiting for them to pay, or paid with the earnings credited. The screens
 * showing it poll the job until it is paid. Renders nothing before the job is
 * completed or for any other outcome (cancelled, disputed, refunded).
 */
export function PaymentStatusNotice({ status }: { status: BookingStatus }) {
  if (status === 'PAYMENT_COMPLETED') {
    return (
      <Alert severity="success" role="status">
        <AlertTitle>Paid — your earnings have been credited</AlertTitle>
        The customer has paid for this job.
      </Alert>
    );
  }
  if (isAwaitingPayment(status)) {
    return (
      <Alert severity="info" role="status">
        <AlertTitle>Waiting for the customer to pay</AlertTitle>
        This updates on its own once their payment goes through.
      </Alert>
    );
  }
  return null;
}
