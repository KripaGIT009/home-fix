import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
import { Alert, Box, Button, InputAdornment, Stack, TextField, Typography } from '@mui/material';
import LinkOffRoundedIcon from '@mui/icons-material/LinkOffRounded';
import { isApiError } from '@api/client';
import { QueryStateView } from '@components/QueryStateView';
import { StandalonePage } from '@components/StandalonePage';
import { ROLE_LABELS } from '@config/roles';
import { formatDateTime } from '@lib/format';
import type { InvitationPreview } from './api';
import { INVITATION_EXPIRED_CODE } from './constants';
import { LabelledField, PasswordInput } from './FormFields';
import { useAcceptInvitation, useInvitation } from './hooks';
import { toE164 } from './phone';
import {
  existingAccountInvitationSchema,
  newAccountInvitationSchema,
  type ExistingAccountInvitationFormValues,
  type NewAccountInvitationFormValues,
} from './schemas';

/**
 * Accept a staff invitation (email-auth Requirements 6.3, 6.4), the public page
 * an emailed link opens. It shows the role, the inviter and the address, then
 * asks a new invitee for a display name, mobile number and password, or an
 * existing account for its current password only. Accepting signs the person
 * in with the role and opens their home module.
 *
 * A used, revoked or expired link answers 410 INVITATION_EXPIRED, on opening or
 * on submitting; both end in the same "ask for a new one" state.
 */
export function AcceptInvitationScreen() {
  const { token = '' } = useParams<{ token: string }>();
  const navigate = useNavigate();
  const invitationQuery = useInvitation(token);
  const accept = useAcceptInvitation();

  const expired =
    (invitationQuery.isError && invitationQuery.error.code === INVITATION_EXPIRED_CODE) ||
    (accept.isError && accept.error.code === INVITATION_EXPIRED_CODE);

  if (expired) {
    return (
      <StandalonePage title="This invitation can no longer be used">
        <Stack spacing={2} alignItems="flex-start">
          <Box sx={{ color: 'text.secondary' }}>
            <LinkOffRoundedIcon sx={{ fontSize: 48 }} aria-hidden />
          </Box>
          <Typography variant="body2" color="text.secondary">
            The link has expired, was already used, or was revoked. Invitations last 7 days and work
            once. Ask the admin who invited you to send a new one.
          </Typography>
          <Button component={RouterLink} to="/login" variant="outlined">
            Go to sign in
          </Button>
        </Stack>
      </StandalonePage>
    );
  }

  const invitation = invitationQuery.data;

  return (
    <StandalonePage
      title="Join HomeFix Operations"
      description={
        invitation
          ? `${invitation.invitedByName ?? 'A HomeFix admin'} invited you to join as ${ROLE_LABELS[invitation.role]}.`
          : undefined
      }
    >
      <QueryStateView
        isLoading={invitationQuery.isLoading}
        isError={invitationQuery.isError}
        error={invitationQuery.error}
        onRetry={() => void invitationQuery.refetch()}
      >
        {invitation ? (
          <Stack spacing={2.5}>
            <Box
              sx={{
                display: 'grid',
                gridTemplateColumns: 'auto 1fr',
                columnGap: 2,
                rowGap: 0.75,
                p: 2,
                borderRadius: 2,
                bgcolor: 'action.hover',
              }}
            >
              <Typography variant="body2" color="text.secondary">
                Email
              </Typography>
              <Typography variant="body2" fontWeight={600} sx={{ wordBreak: 'break-all' }}>
                {invitation.email}
              </Typography>
              <Typography variant="body2" color="text.secondary">
                Role
              </Typography>
              <Typography variant="body2" fontWeight={600}>
                {ROLE_LABELS[invitation.role]}
              </Typography>
              <Typography variant="body2" color="text.secondary">
                Invited by
              </Typography>
              <Typography variant="body2" fontWeight={600}>
                {invitation.invitedByName ?? 'A HomeFix admin'}
              </Typography>
              <Typography variant="body2" color="text.secondary">
                Expires
              </Typography>
              <Typography variant="body2" fontWeight={600}>
                {formatDateTime(invitation.expiresAt)}
              </Typography>
            </Box>

            {invitation.existingAccount ? (
              <ExistingAccountForm
                invitation={invitation}
                isPending={accept.isPending}
                error={accept.error}
                onSubmit={(values) =>
                  accept.mutate(
                    { token, email: invitation.email, payload: { password: values.password } },
                    { onSuccess: () => navigate('/', { replace: true }) },
                  )
                }
              />
            ) : (
              <NewAccountForm
                isPending={accept.isPending}
                error={accept.error}
                onSubmit={(values) =>
                  accept.mutate(
                    {
                      token,
                      email: invitation.email,
                      payload: {
                        displayName: values.displayName.trim(),
                        mobileNumber: toE164(values.mobileNumber),
                        password: values.password,
                      },
                    },
                    { onSuccess: () => navigate('/', { replace: true }) },
                  )
                }
              />
            )}
          </Stack>
        ) : null}
      </QueryStateView>
    </StandalonePage>
  );
}

/** The acceptance call's error, when it is not one a field shows. */
function formErrorMessage(error: unknown, fieldCodes: readonly string[]): string | null {
  if (!error) return null;
  if (isApiError(error)) return fieldCodes.includes(error.code) ? null : error.message;
  return 'Something went wrong. Please try again.';
}

interface ExistingAccountFormProps {
  invitation: InvitationPreview;
  isPending: boolean;
  error: unknown;
  onSubmit: (values: ExistingAccountInvitationFormValues) => void;
}

/**
 * The invited email already has an account (Requirement 6.4): its current
 * password proves it is theirs, and the role is added to it. An account with no
 * password yet (mobile OTP only) answers 409 PASSWORD_NOT_SET, whose message
 * says what to do.
 */
function ExistingAccountForm({ invitation, isPending, error, onSubmit }: ExistingAccountFormProps) {
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<ExistingAccountInvitationFormValues>({
    resolver: zodResolver(existingAccountInvitationSchema),
    defaultValues: { password: '' },
  });

  const wrongPassword = isApiError(error) && error.code === 'INVALID_CREDENTIALS';
  const message = formErrorMessage(error, ['INVALID_CREDENTIALS']);

  return (
    <Stack
      component="form"
      spacing={2}
      onSubmit={(event) => void handleSubmit(onSubmit)(event)}
      noValidate
    >
      <Typography variant="body2" color="text.secondary">
        {invitation.email} already has a HomeFix account. Enter its password to add the role to it.
      </Typography>
      {message ? <Alert severity="error">{message}</Alert> : null}
      <LabelledField label="Password">
        <PasswordInput
          registration={register('password')}
          ariaLabel="Password"
          autoComplete="current-password"
          error={
            errors.password?.message ?? (wrongPassword ? 'That password is not right.' : undefined)
          }
        />
      </LabelledField>
      <Button type="submit" variant="contained" size="large" fullWidth disabled={isPending}>
        {isPending ? 'Joining…' : 'Accept and sign in'}
      </Button>
    </Stack>
  );
}

interface NewAccountFormProps {
  isPending: boolean;
  error: unknown;
  onSubmit: (values: NewAccountInvitationFormValues) => void;
}

/**
 * A new account for the invitee (Requirement 6.3). The email needs no code:
 * the link that reached it proves the person controls it.
 */
function NewAccountForm({ isPending, error, onSubmit }: NewAccountFormProps) {
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<NewAccountInvitationFormValues>({
    resolver: zodResolver(newAccountInvitationSchema),
    defaultValues: { displayName: '', mobileNumber: '', password: '', confirmPassword: '' },
    mode: 'onBlur',
  });

  // Server answers that belong to one field are shown under it.
  const mobileError =
    isApiError(error) && error.code === 'MOBILE_IN_USE' ? error.message : undefined;
  const passwordError =
    isApiError(error) && error.code === 'WEAK_PASSWORD' ? error.message : undefined;
  const message = formErrorMessage(error, ['MOBILE_IN_USE', 'WEAK_PASSWORD']);

  return (
    <Stack
      component="form"
      spacing={2}
      onSubmit={(event) => void handleSubmit(onSubmit)(event)}
      noValidate
    >
      {message ? <Alert severity="error">{message}</Alert> : null}

      <LabelledField label="Your name">
        <TextField
          autoComplete="name"
          fullWidth
          error={Boolean(errors.displayName)}
          helperText={errors.displayName?.message ?? 'Shown to colleagues in the console.'}
          inputProps={{ 'aria-label': 'Your name' }}
          {...register('displayName')}
        />
      </LabelledField>

      <LabelledField label="Mobile number">
        <TextField
          type="tel"
          autoComplete="tel"
          fullWidth
          placeholder="98765 43210"
          error={Boolean(errors.mobileNumber ?? mobileError)}
          helperText={errors.mobileNumber?.message ?? mobileError ?? ' '}
          InputProps={{
            startAdornment: (
              <InputAdornment position="start">
                <Typography
                  variant="body1"
                  fontWeight={600}
                  sx={{ pr: 1.25, mr: 1.25, borderRight: 1, borderColor: 'divider' }}
                >
                  +91
                </Typography>
              </InputAdornment>
            ),
          }}
          inputProps={{ inputMode: 'tel', 'aria-label': 'Mobile number' }}
          {...register('mobileNumber')}
        />
      </LabelledField>

      <LabelledField label="Password">
        <PasswordInput
          registration={register('password')}
          ariaLabel="Password"
          autoComplete="new-password"
          error={errors.password?.message ?? passwordError}
          helperText="8–72 characters, with a letter and a number."
        />
      </LabelledField>

      <LabelledField label="Confirm password">
        <PasswordInput
          registration={register('confirmPassword')}
          ariaLabel="Confirm password"
          autoComplete="new-password"
          error={errors.confirmPassword?.message}
        />
      </LabelledField>

      <Button type="submit" variant="contained" size="large" fullWidth disabled={isPending}>
        {isPending ? 'Creating account…' : 'Create account and sign in'}
      </Button>
    </Stack>
  );
}
