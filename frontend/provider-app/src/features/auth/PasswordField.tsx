import { forwardRef, useState, type ReactNode } from 'react';
import type { UseFormRegisterReturn } from 'react-hook-form';
import { IconButton, InputAdornment, TextField } from '@mui/material';
import VisibilityRoundedIcon from '@mui/icons-material/VisibilityRounded';
import VisibilityOffRoundedIcon from '@mui/icons-material/VisibilityOffRounded';

type PasswordFieldProps = Omit<UseFormRegisterReturn, 'ref'> & {
  /** `current-password` to sign in or confirm, `new-password` to choose one. */
  autoComplete: 'current-password' | 'new-password';
  label?: string;
  /** Accessible name when there is no visible `label` (the auth screens label above the field). */
  ariaLabel?: string;
  error?: boolean;
  helperText?: ReactNode;
  disabled?: boolean;
  size?: 'small' | 'medium';
};

/**
 * Password input with a show/hide toggle. Spread a react-hook-form
 * `register(...)` onto it.
 */
export const PasswordField = forwardRef<HTMLInputElement, PasswordFieldProps>(
  function PasswordField(
    {
      autoComplete,
      label,
      ariaLabel,
      error = false,
      helperText,
      disabled = false,
      size = 'medium',
      name,
      onChange,
      onBlur,
    },
    ref,
  ) {
    const [visible, setVisible] = useState(false);

    return (
      <TextField
        type={visible ? 'text' : 'password'}
        autoComplete={autoComplete}
        fullWidth
        size={size}
        label={label}
        error={error}
        helperText={helperText}
        disabled={disabled}
        name={name}
        onChange={(event) => void onChange(event)}
        onBlur={(event) => void onBlur(event)}
        inputRef={ref}
        inputProps={ariaLabel ? { 'aria-label': ariaLabel } : undefined}
        InputProps={{
          endAdornment: (
            <InputAdornment position="end">
              <IconButton
                edge="end"
                size="small"
                onClick={() => setVisible((shown) => !shown)}
                aria-label={visible ? 'Hide password' : 'Show password'}
                disabled={disabled}
              >
                {visible ? <VisibilityOffRoundedIcon /> : <VisibilityRoundedIcon />}
              </IconButton>
            </InputAdornment>
          ),
        }}
      />
    );
  },
);
