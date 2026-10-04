import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
} from '@mui/material';
import { tenantErrorMessage } from './model';

interface ConfirmRemoveDialogProps {
  title: string;
  /** What removing means, e.g. that assigned jobs are unaffected (Requirement MT-3.3). */
  message: string;
  confirmLabel: string;
  /** Button text while the call runs; defaults to "Removing…". */
  pendingLabel?: string;
  isPending: boolean;
  error: unknown;
  onConfirm: () => void;
  onClose: () => void;
}

/**
 * Confirmation before removing something that takes effect at once: a Tenant
 * member, or a staff invitation (email-auth Requirement 6.5).
 */
export function ConfirmRemoveDialog({
  title,
  message,
  confirmLabel,
  pendingLabel = 'Removing…',
  isPending,
  error,
  onConfirm,
  onClose,
}: ConfirmRemoveDialogProps) {
  return (
    <Dialog open onClose={isPending ? undefined : onClose} maxWidth="xs" fullWidth>
      <DialogTitle>{title}</DialogTitle>
      <DialogContent dividers>
        <DialogContentText>{message}</DialogContentText>
        {error ? (
          <Alert severity="error" sx={{ mt: 2 }}>
            {tenantErrorMessage(error)}
          </Alert>
        ) : null}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={isPending}>
          Keep
        </Button>
        <Button color="error" variant="contained" onClick={onConfirm} disabled={isPending}>
          {isPending ? pendingLabel : confirmLabel}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
