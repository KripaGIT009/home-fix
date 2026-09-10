import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Alert, Box, Button, InputAdornment, Stack, TextField, Typography } from '@mui/material';
import ArrowForwardRoundedIcon from '@mui/icons-material/ArrowForwardRounded';
import { mobileSchema, type MobileFormValues } from './schemas';

interface MobileStepProps {
  onSubmit: (values: MobileFormValues) => void;
  isSubmitting: boolean;
  errorMessage: string | null;
}

/**
 * Step 1 of the login flow: collect and validate a mobile number, then request
 * an OTP (Requirement 1.1).
 */
export function MobileStep({ onSubmit, isSubmitting, errorMessage }: MobileStepProps) {
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
      spacing={2}
      onSubmit={(event) => void handleSubmit(onSubmit)(event)}
      noValidate
    >
      {errorMessage ? <Alert severity="error">{errorMessage}</Alert> : null}

      <Box>
        <Typography variant="subtitle2" sx={{ mb: 0.75, color: 'text.primary' }}>
          Mobile number
        </Typography>
        <TextField
          type="tel"
          autoComplete="tel"
          fullWidth
          placeholder="98765 43210"
          error={Boolean(errors.mobileNumber)}
          helperText={errors.mobileNumber?.message ?? 'We will text you a verification code.'}
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
          inputProps={{
            inputMode: 'tel',
            'aria-label': 'Mobile number',
            style: { fontSize: '1.0625rem', letterSpacing: '0.02em' },
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
        {isSubmitting ? 'Sending code…' : 'Send OTP'}
      </Button>
    </Stack>
  );
}
