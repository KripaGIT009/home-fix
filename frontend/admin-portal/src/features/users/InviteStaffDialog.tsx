import { Controller, useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Stack,
  TextField,
} from '@mui/material';
import { ROLE_LABELS, invitableRoles } from '@config/roles';
import { useAuthStore } from '@stores/authStore';
import { useCreateInvitation } from './hooks';
import { invitationSchema, type InvitationFormValues } from './schemas';

interface InviteStaffDialogProps {
  onClose: () => void;
  /** The invitation was emailed; the address is for a snackbar. */
  onInvited: (email: string) => void;
}

/** What each invitable role opens, so the inviter picks knowingly. */
const ROLE_HINTS: Partial<Record<InvitationFormValues['role'], string>> = {
  ADMIN: 'Every operational module, including Users and Tenants.',
  FINANCE_ADMIN: 'Payments, refunds and reports.',
  DISPATCHER: 'Bookings and dispatch rules.',
  SUPPORT_AGENT: 'Bookings, complaints and review moderation.',
};

/**
 * Invite a colleague by email with a staff role (email-auth Requirement 6.1).
 * The invitee sets their own name, mobile and password from the emailed link;
 * nobody chooses a password for them. ADMIN is offered to a SUPER_ADMIN only.
 */
export function InviteStaffDialog({ onClose, onInvited }: InviteStaffDialogProps) {
  const roles = useAuthStore((state) => state.user?.roles);
  const options = invitableRoles(roles ?? []);
  const invite = useCreateInvitation();

  const {
    control,
    register,
    handleSubmit,
    watch,
    setError,
    formState: { errors },
  } = useForm<InvitationFormValues>({
    resolver: zodResolver(invitationSchema),
    mode: 'onBlur',
    defaultValues: { email: '', role: 'SUPPORT_AGENT' },
  });
  const role = watch('role');

  const onSubmit = (values: InvitationFormValues) => {
    const email = values.email.trim();
    invite.mutate(
      { email, role: values.role },
      {
        onSuccess: () => onInvited(email),
        onError: (error) => {
          if (error.code === 'INVALID_ROLE' || error.code === 'SUPER_ADMIN_REQUIRED') {
            setError('role', { message: error.message });
          }
        },
      },
    );
  };

  const roleError =
    invite.isError && ['INVALID_ROLE', 'SUPER_ADMIN_REQUIRED'].includes(invite.error.code);

  return (
    <Dialog open onClose={invite.isPending ? undefined : onClose} maxWidth="xs" fullWidth>
      <DialogTitle>Invite staff</DialogTitle>
      <form
        noValidate
        onSubmit={(event) => {
          void handleSubmit(onSubmit)(event);
        }}
      >
        <DialogContent dividers>
          <Stack spacing={2}>
            <TextField
              label="Email"
              type="email"
              fullWidth
              required
              autoComplete="off"
              {...register('email')}
              error={Boolean(errors.email)}
              helperText={
                errors.email?.message ?? 'They get a link to set up their account; it lasts 7 days.'
              }
            />
            <Controller
              control={control}
              name="role"
              render={({ field }) => (
                <TextField
                  label="Role"
                  select
                  fullWidth
                  required
                  {...field}
                  error={Boolean(errors.role)}
                  helperText={errors.role?.message ?? ROLE_HINTS[role] ?? ' '}
                >
                  {options.map((option) => (
                    <MenuItem key={option} value={option}>
                      {ROLE_LABELS[option]}
                    </MenuItem>
                  ))}
                </TextField>
              )}
            />
          </Stack>
          {invite.isError && !roleError ? (
            <Alert severity="error" sx={{ mt: 2 }}>
              {invite.error.message}
            </Alert>
          ) : null}
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose} disabled={invite.isPending}>
            Cancel
          </Button>
          <Button type="submit" variant="contained" disabled={invite.isPending}>
            {invite.isPending ? 'Sending…' : 'Send invitation'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
