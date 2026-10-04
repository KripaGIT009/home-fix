import { useState, type ReactNode } from 'react';
import type { UseFormRegisterReturn } from 'react-hook-form';
import { Box, IconButton, InputAdornment, TextField, Typography } from '@mui/material';
import LockOutlinedIcon from '@mui/icons-material/LockOutlined';
import VisibilityOutlinedIcon from '@mui/icons-material/VisibilityOutlined';
import VisibilityOffOutlinedIcon from '@mui/icons-material/VisibilityOffOutlined';

/**
 * Field pieces shared by the sign-in card's forms: sign-in, password reset,
 * the agency applicant's sign-up and the invitation acceptance. They keep the
 * card's look — a bold label above an unlabelled outlined field — in one place.
 */

interface LabelledFieldProps {
  label: string;
  /** Rendered at the right of the label, e.g. a "Forgot password?" link. */
  action?: ReactNode;
  children: ReactNode;
}

/** A bold label above its field, as the sign-in card lays fields out. */
export function LabelledField({ label, action, children }: LabelledFieldProps) {
  return (
    <Box>
      <Box
        sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', mb: 0.75 }}
      >
        <Typography variant="subtitle2" sx={{ color: 'text.primary' }}>
          {label}
        </Typography>
        {action}
      </Box>
      {children}
    </Box>
  );
}

interface PasswordInputProps {
  /** The react-hook-form registration of the field. */
  registration: UseFormRegisterReturn;
  /** Accessible name; the visible label is the LabelledField's. */
  ariaLabel: string;
  /** current-password to sign in, new-password to set one. */
  autoComplete: 'current-password' | 'new-password';
  error?: string | undefined;
  /** Shown while there is no error. */
  helperText?: string;
  disabled?: boolean;
}

/** A password field with a lock icon and a show/hide toggle. */
export function PasswordInput({
  registration,
  ariaLabel,
  autoComplete,
  error,
  helperText = ' ',
  disabled = false,
}: PasswordInputProps) {
  const [visible, setVisible] = useState(false);

  return (
    <TextField
      type={visible ? 'text' : 'password'}
      autoComplete={autoComplete}
      fullWidth
      placeholder="••••••••"
      disabled={disabled}
      error={Boolean(error)}
      helperText={error ?? helperText}
      InputProps={{
        startAdornment: (
          <InputAdornment position="start">
            <LockOutlinedIcon fontSize="small" sx={{ color: 'text.secondary' }} />
          </InputAdornment>
        ),
        endAdornment: (
          <InputAdornment position="end">
            <IconButton
              onClick={() => setVisible((shown) => !shown)}
              edge="end"
              size="small"
              aria-label={visible ? 'Hide password' : 'Show password'}
            >
              {visible ? (
                <VisibilityOffOutlinedIcon fontSize="small" />
              ) : (
                <VisibilityOutlinedIcon fontSize="small" />
              )}
            </IconButton>
          </InputAdornment>
        ),
      }}
      inputProps={{ 'aria-label': ariaLabel }}
      {...registration}
    />
  );
}
