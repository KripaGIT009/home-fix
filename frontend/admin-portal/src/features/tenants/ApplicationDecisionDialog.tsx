import { useState } from 'react';
import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  TextField,
} from '@mui/material';
import { useDecideApplication } from './hooks';
import { tenantErrorMessage, type Tenant } from './model';

/** The server's bound on a rejection reason (email-auth Requirement 5.4). */
const REASON_MAX_LENGTH = 500;

interface ApplicationDecisionDialogProps {
  tenant: Tenant;
  /** Which decision the admin chose from the row. */
  decision: 'approve' | 'reject';
  onClose: () => void;
  /** The decision went through; the message is for a snackbar. */
  onDecided: (message: string) => void;
}

/**
 * Confirm an agency application's approval, or reject it with a reason
 * (email-auth Requirements 5.4, 5.6). Both are final and email the applicant,
 * so neither happens on a single click from the list. The reason is required
 * and is what the applicant reads on their status page.
 */
export function ApplicationDecisionDialog({
  tenant,
  decision,
  onClose,
  onDecided,
}: ApplicationDecisionDialogProps) {
  const decide = useDecideApplication(tenant.id);
  const [reason, setReason] = useState('');
  const [touched, setTouched] = useState(false);
  const approving = decision === 'approve';
  const trimmed = reason.trim();
  const reasonInvalid = !approving && touched && trimmed.length === 0;

  const handleConfirm = () => {
    if (approving) {
      decide.mutate({ approve: true }, { onSuccess: () => onDecided(`${tenant.name} approved`) });
      return;
    }
    setTouched(true);
    if (!trimmed) return;
    decide.mutate(
      { approve: false, reason: trimmed },
      { onSuccess: () => onDecided(`${tenant.name} rejected`) },
    );
  };

  return (
    <Dialog open onClose={decide.isPending ? undefined : onClose} maxWidth="sm" fullWidth>
      <DialogTitle>{approving ? `Approve ${tenant.name}?` : `Reject ${tenant.name}?`}</DialogTitle>
      <DialogContent dividers>
        <DialogContentText>
          {approving
            ? 'The agency becomes active and starts receiving requests in its area. The applicant becomes its administrator and is emailed; they open the agency portal at their next sign-in.'
            : 'The applicant is emailed the reason below and sees it when they sign in. They can correct their details and apply again.'}
        </DialogContentText>
        {approving ? null : (
          <TextField
            label="Reason"
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            onBlur={() => setTouched(true)}
            fullWidth
            multiline
            minRows={3}
            required
            sx={{ mt: 2 }}
            inputProps={{ maxLength: REASON_MAX_LENGTH }}
            error={reasonInvalid}
            helperText={
              reasonInvalid
                ? 'A reason is required to reject an application.'
                : `${reason.length}/${REASON_MAX_LENGTH} · Shared with the applicant.`
            }
          />
        )}
        {decide.isError ? (
          <Alert severity="error" sx={{ mt: 2 }}>
            {tenantErrorMessage(decide.error)}
          </Alert>
        ) : null}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={decide.isPending}>
          Cancel
        </Button>
        <Button
          variant="contained"
          color={approving ? 'primary' : 'error'}
          onClick={handleConfirm}
          disabled={decide.isPending}
        >
          {decide.isPending ? 'Saving…' : approving ? 'Approve' : 'Reject application'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
