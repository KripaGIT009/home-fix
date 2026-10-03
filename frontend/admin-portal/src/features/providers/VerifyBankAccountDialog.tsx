import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
} from '@mui/material';
import { isApiError } from '@api/client';
import type { AdminProvider, ProviderBankAccount } from './api';
import { useVerifyProviderBankAccount } from './hooks';

interface VerifyBankAccountDialogProps {
  provider: AdminProvider & { bankAccount: ProviderBankAccount };
  onClose: () => void;
  /** The account was marked verified; the name is for the confirmation. */
  onVerified: (providerName: string) => void;
}

/** Plain-language message for a failed verification. */
function verifyErrorMessage(error: unknown): string {
  if (!isApiError(error)) return 'Could not mark the bank account verified. Try again.';
  if (error.status === 404 && error.code === 'BANK_ACCOUNT_NOT_FOUND') {
    return 'This provider no longer has a bank account on file. The list has been refreshed.';
  }
  return error.message;
}

/**
 * Confirm before marking a provider's bank account verified: once verified,
 * settlements are paid into it, so the admin confirms they have checked it.
 * Errors keep the dialog open with the reason.
 */
export function VerifyBankAccountDialog({
  provider,
  onClose,
  onVerified,
}: VerifyBankAccountDialogProps) {
  const verify = useVerifyProviderBankAccount();

  const handleConfirm = () => {
    verify.mutate(provider.id, { onSuccess: () => onVerified(provider.displayName) });
  };

  return (
    <Dialog
      open
      onClose={verify.isPending ? undefined : onClose}
      maxWidth="xs"
      fullWidth
      aria-labelledby="verify-bank-account-title"
    >
      <DialogTitle id="verify-bank-account-title">Mark bank account verified?</DialogTitle>
      <DialogContent>
        <DialogContentText>
          Confirm that you have checked that account <strong>{provider.bankAccount.masked}</strong>{' '}
          belongs to <strong>{provider.displayName}</strong>. Once verified, the provider&apos;s
          settlements are paid into it.
        </DialogContentText>
        {verify.isError ? (
          <Alert severity="error" sx={{ mt: 2 }}>
            {verifyErrorMessage(verify.error)}
          </Alert>
        ) : null}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={verify.isPending}>
          Cancel
        </Button>
        <Button
          variant="contained"
          color="success"
          onClick={handleConfirm}
          disabled={verify.isPending}
        >
          {verify.isPending ? 'Marking…' : 'Mark verified'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
