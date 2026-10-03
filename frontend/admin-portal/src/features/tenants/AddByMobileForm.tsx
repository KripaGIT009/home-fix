import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Alert, Box, Button, Stack, TextField } from '@mui/material';
import { normaliseMobile, tenantErrorMessage } from './model';
import { mobileSchema, type MobileFormValues } from './schemas';

interface AddByMobileFormProps {
  /** Field label, e.g. "Provider's mobile number". */
  label: string;
  /** Submit button text, e.g. "Add provider". */
  submitLabel: string;
  /** Explains who can be added; shown under the field until there is an error. */
  helperText: string;
  /** Sends the E.164 number; resolves on success, rejects with the ApiError. */
  onAdd: (mobileNumber: string) => Promise<unknown>;
  isPending: boolean;
  /** The last failure, shown with the Tenant error copy (Requirements MT-2, MT-3). */
  error: unknown;
}

/**
 * Add a member by mobile number — the only handle a Platform_Admin or
 * Tenant_Admin has for someone else's account (Requirements MT-2.2, MT-3.2).
 * The number is normalised to E.164 before it is sent, because accounts store
 * it that way and "98765 43210" would otherwise answer a misleading 404.
 */
export function AddByMobileForm({
  label,
  submitLabel,
  helperText,
  onAdd,
  isPending,
  error,
}: AddByMobileFormProps) {
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<MobileFormValues>({
    resolver: zodResolver(mobileSchema),
    defaultValues: { mobileNumber: '' },
  });

  const onSubmit = (values: MobileFormValues) => {
    const mobile = normaliseMobile(values.mobileNumber);
    if (!mobile) return;
    onAdd(mobile).then(
      () => reset({ mobileNumber: '' }),
      // The mutation keeps the error for the alert below.
      () => undefined,
    );
  };

  return (
    <Box
      component="form"
      noValidate
      onSubmit={(event) => {
        void handleSubmit(onSubmit)(event);
      }}
    >
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} alignItems="flex-start">
        <TextField
          size="small"
          label={label}
          placeholder="98765 43210"
          inputMode="tel"
          autoComplete="off"
          fullWidth
          {...register('mobileNumber')}
          error={Boolean(errors.mobileNumber)}
          helperText={errors.mobileNumber?.message ?? helperText}
        />
        <Button
          type="submit"
          variant="contained"
          disabled={isPending}
          sx={{ flexShrink: 0, whiteSpace: 'nowrap' }}
        >
          {isPending ? 'Adding…' : submitLabel}
        </Button>
      </Stack>
      {error ? (
        <Alert severity="error" sx={{ mt: 1.5 }}>
          {tenantErrorMessage(error)}
        </Alert>
      ) : null}
    </Box>
  );
}
