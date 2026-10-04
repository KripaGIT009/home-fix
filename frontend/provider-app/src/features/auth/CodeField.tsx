import { forwardRef, type ReactNode } from 'react';
import type { UseFormRegisterReturn } from 'react-hook-form';
import { TextField } from '@mui/material';
import { OTP_LENGTH } from './constants';

type CodeFieldProps = Omit<UseFormRegisterReturn, 'ref'> & {
  error?: boolean;
  helperText?: ReactNode;
  disabled?: boolean;
};

/**
 * The 6-digit code input shared by the OTP step and the emailed-code screens
 * (sign-up, password reset, email change). Spread a react-hook-form
 * `register(...)` onto it.
 */
export const CodeField = forwardRef<HTMLInputElement, CodeFieldProps>(function CodeField(
  { error = false, helperText = ' ', disabled = false, name, onChange, onBlur },
  ref,
) {
  return (
    <TextField
      autoComplete="one-time-code"
      fullWidth
      placeholder={'0'.repeat(OTP_LENGTH)}
      error={error}
      helperText={helperText}
      disabled={disabled}
      name={name}
      onChange={(event) => void onChange(event)}
      onBlur={(event) => void onBlur(event)}
      inputRef={ref}
      inputProps={{
        inputMode: 'numeric',
        maxLength: OTP_LENGTH,
        'aria-label': 'Verification code',
        style: {
          // Wide tracking reads as a code field without a per-digit input grid,
          // which keeps paste and password managers working.
          textAlign: 'center',
          fontSize: '1.5rem',
          fontWeight: 700,
          letterSpacing: '0.5em',
          textIndent: '0.5em',
        },
      }}
    />
  );
});
