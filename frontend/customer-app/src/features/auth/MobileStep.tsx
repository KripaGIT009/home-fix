import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Alert, Box, Button, InputAdornment, Stack, TextField, Typography } from '@mui/material';
import ArrowForwardRoundedIcon from '@mui/icons-material/ArrowForwardRounded';
import { brand } from '@lib/theme';
import { mobileSchema, type MobileFormValues } from './schemas';

interface MobileStepProps {
  onSubmit: (values: MobileFormValues) => void;
  isSubmitting: boolean;
  errorMessage: string | null;
  /** `warning` for "can't reach us" problems, `error` for a rejected request. */
  errorSeverity?: 'warning' | 'error';
}

/**
 * Step 1 of the login flow: collect and validate a mobile number, then request
 * an OTP (Requirement 1.1).
 */
export function MobileStep({
  onSubmit,
  isSubmitting,
  errorMessage,
  errorSeverity = 'error',
}: MobileStepProps) {
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<MobileFormValues>({
    resolver: zodResolver(mobileSchema),
    defaultValues: { mobileNumber: '' },
    mode: 'onBlur',
  });

  return (
    <Stack
      component="form"
      spacing={2.5}
      onSubmit={(event) => void handleSubmit(onSubmit)(event)}
      noValidate
    >
      {errorMessage ? (
        <Alert severity={errorSeverity} role="alert">
          {errorMessage}
        </Alert>
      ) : null}

      <Box>
        <Typography
          component="label"
          htmlFor="login-mobile"
          variant="subtitle2"
          sx={{ display: 'block', mb: 1, color: 'text.primary' }}
        >
          Mobile number
        </Typography>
        <TextField
          id="login-mobile"
          type="tel"
          autoComplete="tel-national"
          fullWidth
          placeholder="98765 43210"
          error={Boolean(errors.mobileNumber)}
          helperText={errors.mobileNumber?.message ?? 'We’ll text you a 6-digit code to verify it.'}
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
          {...register('mobileNumber')}
        />
      </Box>

      <Button
        type="submit"
        variant="contained"
        size="large"
        fullWidth
        disabled={isSubmitting}
        endIcon={isSubmitting ? undefined : <ArrowForwardRoundedIcon />}
      >
        {isSubmitting ? 'Sending code…' : 'Continue'}
      </Button>
    </Stack>
  );
}
