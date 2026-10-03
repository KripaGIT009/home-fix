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
  isPending: boolean;
  error: unknown;
  onConfirm: () => void;
  onClose: () => void;
}

/** Confirmation before removing a Tenant member, which takes effect at once. */
export function ConfirmRemoveDialog({
  title,
  message,
  confirmLabel,
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
          {isPending ? 'Removing…' : confirmLabel}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
