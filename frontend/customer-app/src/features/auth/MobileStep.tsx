import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Alert, Button, Stack } from '@mui/material';
import ArrowForwardRoundedIcon from '@mui/icons-material/ArrowForwardRounded';
import { MobileNumberField } from './AuthFields';
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

      <MobileNumberField
        id="login-mobile"
        error={Boolean(errors.mobileNumber)}
        helperText={errors.mobileNumber?.message ?? 'We’ll text you a 6-digit code to verify it.'}
        {...register('mobileNumber')}
      />

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
