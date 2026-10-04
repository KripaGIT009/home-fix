import { forwardRef, useState, type ReactNode } from 'react';
import { Box, IconButton, InputAdornment, TextField, Typography } from '@mui/material';
import type { TextFieldProps } from '@mui/material';
import VisibilityRoundedIcon from '@mui/icons-material/VisibilityRounded';
import VisibilityOffRoundedIcon from '@mui/icons-material/VisibilityOffRounded';
import { brand } from '@lib/theme';

/**
 * Form fields shared by the auth screens. Each renders its label above the
 * input, as the mobile-number step always has, and forwards its ref to the
 * `<input>` so react-hook-form can focus the first invalid field.
 */

type LabelledFieldProps = Omit<TextFieldProps, 'id' | 'label'> & {
  id: string;
  label: ReactNode;
};

/** The label above an auth form input. */
function FieldLabel({ htmlFor, children }: { htmlFor: string; children: ReactNode }) {
  return (
    <Typography
      component="label"
      htmlFor={htmlFor}
      variant="subtitle2"
      sx={{ display: 'block', mb: 1, color: 'text.primary' }}
    >
      {children}
    </Typography>
  );
}

/** A plain text input (name, email) with its label above it. */
export const LabelledTextField = forwardRef<HTMLInputElement, LabelledFieldProps>(
  function LabelledTextField({ id, label, ...props }, ref) {
    return (
      <Box>
        <FieldLabel htmlFor={id}>{label}</FieldLabel>
        <TextField id={id} fullWidth inputRef={ref} {...props} />
      </Box>
    );
  },
);

/** A password input with a show/hide toggle. */
export const PasswordField = forwardRef<HTMLInputElement, LabelledFieldProps>(
  function PasswordField({ id, label, ...props }, ref) {
    const [visible, setVisible] = useState(false);

    return (
      <Box>
        <FieldLabel htmlFor={id}>{label}</FieldLabel>
        <TextField
          id={id}
          fullWidth
          inputRef={ref}
          {...props}
          type={visible ? 'text' : 'password'}
          InputProps={{
            endAdornment: (
              <InputAdornment position="end">
                <IconButton
                  edge="end"
                  aria-label={visible ? 'Hide password' : 'Show password'}
                  aria-controls={id}
                  onClick={() => setVisible((shown) => !shown)}
                >
                  {visible ? <VisibilityOffRoundedIcon /> : <VisibilityRoundedIcon />}
                </IconButton>
              </InputAdornment>
            ),
          }}
        />
      </Box>
    );
  },
);

/**
 * The +91 mobile-number input (Requirement 1.1). The value stays as typed; the
 * caller normalises it to E.164 with `toE164` before it is sent.
 */
export const MobileNumberField = forwardRef<
  HTMLInputElement,
  Omit<LabelledFieldProps, 'label'> & { label?: ReactNode }
>(function MobileNumberField({ id, label = 'Mobile number', ...props }, ref) {
  return (
    <Box>
      <FieldLabel htmlFor={id}>{label}</FieldLabel>
      <TextField
        id={id}
        type="tel"
        autoComplete="tel-national"
        fullWidth
        placeholder="98765 43210"
        inputRef={ref}
        {...props}
        InputProps={{
          startAdornment: (
            <InputAdornment position="start" sx={{ mr: 0, height: 'auto', maxHeight: 'none' }}>
              <Box
                sx={{
                  pr: 1.5,
                  mr: 1.5,
                  py: 0.5,
                  borderRight: `1px solid ${brand.line}`,
                  fontWeight: 700,
                  color: 'text.primary',
                  fontSize: '1.0625rem',
                }}
              >
                +91
              </Box>
            </InputAdornment>
          ),
        }}
        inputProps={{
          inputMode: 'tel',
          maxLength: 14,
          style: {
            fontSize: '1.125rem',
            letterSpacing: '0.03em',
            paddingTop: 16,
            paddingBottom: 16,
          },
        }}
      />
    </Box>
  );
});
