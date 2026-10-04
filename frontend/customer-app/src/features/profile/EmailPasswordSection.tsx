import { useState, type ReactNode } from 'react';
import {
  Alert,
  Box,
  Button,
  Card,
  Collapse,
  Divider,
  Skeleton,
  Stack,
  Typography,
} from '@mui/material';
import AlternateEmailRoundedIcon from '@mui/icons-material/AlternateEmailRounded';
import PasswordRoundedIcon from '@mui/icons-material/PasswordRounded';
import { IconTile, InlineError } from '@components/StateViews';
import { useAccount } from './hooks';
import { EmailChangeForm } from './EmailChangeForm';
import { PasswordChangeForm } from './PasswordChangeForm';

type Editor = 'email' | 'password' | null;

/**
 * Profile "Email & password" (email-auth Requirement 4.3): lets someone who
 * signed up by mobile OTP add an email and password, and anyone change them.
 * One editor opens at a time, inline under its row.
 */
export function EmailPasswordSection() {
  const account = useAccount();
  const [editor, setEditor] = useState<Editor>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const open = (next: Editor) => {
    setNotice(null);
    setEditor(next);
  };

  return (
    <Card component="section" aria-labelledby="email-password-heading">
      <Box sx={{ px: { xs: 2, md: 3 }, pt: { xs: 2, md: 2.5 }, pb: 1.5 }}>
        <Typography id="email-password-heading" variant="h5" component="h2">
          Email & password
        </Typography>
        <Typography variant="body2" color="text.secondary">
          Sign in with your email and a password as well as your mobile number.
        </Typography>
      </Box>

      {notice ? (
        <Alert
          severity="success"
          sx={{ mx: { xs: 2, md: 3 }, mb: 1.5 }}
          onClose={() => setNotice(null)}
        >
          {notice}
        </Alert>
      ) : null}

      {account.isPending ? (
        <Stack spacing={1.5} sx={{ px: { xs: 2, md: 3 }, pb: 2.5 }}>
          <Skeleton variant="rounded" height={48} />
          <Skeleton variant="rounded" height={48} />
        </Stack>
      ) : account.isError ? (
        <Box sx={{ px: { xs: 2, md: 3 }, pb: 2.5 }}>
          <InlineError
            error={account.error}
            title="Couldn’t load your sign-in details"
            onRetry={() => void account.refetch()}
          />
        </Box>
      ) : (
        <>
          <Divider />
          <CredentialRow
            icon={<AlternateEmailRoundedIcon />}
            label="Email"
            value={account.data.email ?? 'No email added yet'}
            actionLabel={account.data.email ? 'Change' : 'Add email'}
            isOpen={editor === 'email'}
            onAction={() => open(editor === 'email' ? null : 'email')}
          >
            <EmailChangeForm
              account={account.data}
              onCancel={() => setEditor(null)}
              onDone={(updated) => {
                setEditor(null);
                setNotice(`Your email is now ${updated.email ?? 'saved'}.`);
              }}
            />
          </CredentialRow>
          <Divider />
          {account.data.email || account.data.username ? (
            <CredentialRow
              icon={<PasswordRoundedIcon />}
              label="Password"
              value={account.data.hasPassword ? 'Set' : 'Not set yet'}
              actionLabel={account.data.hasPassword ? 'Change' : 'Set password'}
              isOpen={editor === 'password'}
              onAction={() => open(editor === 'password' ? null : 'password')}
            >
              <PasswordChangeForm
                account={account.data}
                onCancel={() => setEditor(null)}
                onAddEmail={() => open('email')}
                onDone={(firstPassword) => {
                  setEditor(null);
                  setNotice(
                    firstPassword
                      ? 'Password set. You can now sign in with your email and password.'
                      : 'Your password has been changed.',
                  );
                }}
              />
            </CredentialRow>
          ) : (
            // A password needs a verified email to sign in with (EMAIL_REQUIRED).
            <CredentialRow
              icon={<PasswordRoundedIcon />}
              label="Password"
              value="Add an email first — you’ll sign in with it and the password."
              actionLabel="Add email"
              isOpen={false}
              onAction={() => open('email')}
            />
          )}
        </>
      )}
    </Card>
  );
}

/** One sign-in detail with its action, and the editor that opens under it. */
function CredentialRow({
  icon,
  label,
  value,
  actionLabel,
  isOpen,
  onAction,
  children,
}: {
  icon: ReactNode;
  label: string;
  value: string;
  actionLabel: string;
  isOpen: boolean;
  onAction: () => void;
  children?: ReactNode;
}) {
  return (
    <Box sx={{ px: { xs: 2, md: 3 }, py: 2 }}>
      <Stack direction="row" spacing={2} alignItems="center">
        <IconTile size={40}>{icon}</IconTile>
        <Box sx={{ flexGrow: 1, minWidth: 0 }}>
          <Typography variant="subtitle1" fontWeight={700}>
            {label}
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ overflowWrap: 'anywhere' }}>
            {value}
          </Typography>
        </Box>
        <Button
          variant={isOpen ? 'text' : 'outlined'}
          size="small"
          onClick={onAction}
          aria-expanded={children ? isOpen : undefined}
          sx={{ flexShrink: 0 }}
        >
          {isOpen ? 'Close' : actionLabel}
        </Button>
      </Stack>
      {children ? (
        <Collapse in={isOpen} unmountOnExit>
          <Box sx={{ pt: 2.5 }}>{children}</Box>
        </Collapse>
      ) : null}
    </Box>
  );
}
