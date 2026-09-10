import { useCallback, useState } from 'react';
import type { UseFormRegister, FieldErrors, UseFormSetValue } from 'react-hook-form';
import { Alert, Button, Stack, TextField, Typography } from '@mui/material';
import MyLocationRoundedIcon from '@mui/icons-material/MyLocationRounded';
import type { ServiceRequestFormValues } from './schemas';

interface AddressFieldsProps {
  register: UseFormRegister<ServiceRequestFormValues>;
  errors: FieldErrors<ServiceRequestFormValues>;
  setValue: UseFormSetValue<ServiceRequestFormValues>;
}

/**
 * Address input for the Service Request screen (Requirement 7.1). Supports
 * either GPS detection (Geolocation API) that stores coordinates, or manual
 * entry of the address lines, city, and PIN code.
 */
export function AddressFields({ register, errors, setValue }: AddressFieldsProps) {
  const [detecting, setDetecting] = useState(false);
  const [detectMessage, setDetectMessage] = useState<{
    severity: 'success' | 'error';
    text: string;
  } | null>(null);

  const handleDetect = useCallback(() => {
    if (!('geolocation' in navigator)) {
      setDetectMessage({
        severity: 'error',
        text: 'Location detection is not supported on this device. Please enter your address.',
      });
      return;
    }

    setDetecting(true);
    setDetectMessage(null);
    navigator.geolocation.getCurrentPosition(
      (position) => {
        const { latitude, longitude } = position.coords;
        setValue('address.latitude', latitude, { shouldDirty: true });
        setValue('address.longitude', longitude, { shouldDirty: true });
        setDetecting(false);
        setDetectMessage({
          severity: 'success',
          text: 'Location detected. Confirm or complete the address below.',
        });
      },
      () => {
        setDetecting(false);
        setDetectMessage({
          severity: 'error',
          text: 'Could not detect your location. Please enter your address manually.',
        });
      },
      { enableHighAccuracy: true, timeout: 10_000 },
    );
  }, [setValue]);

  return (
    <Stack spacing={2}>
      <Stack direction="row" justifyContent="space-between" alignItems="center">
        <Typography variant="subtitle2">Service address</Typography>
        <Button
          type="button"
          size="small"
          startIcon={<MyLocationRoundedIcon />}
          onClick={handleDetect}
          disabled={detecting}
        >
          {detecting ? 'Detecting…' : 'Use my location'}
        </Button>
      </Stack>

      {detectMessage ? <Alert severity={detectMessage.severity}>{detectMessage.text}</Alert> : null}

      <TextField
        label="Address line 1"
        fullWidth
        error={Boolean(errors.address?.line1)}
        helperText={errors.address?.line1?.message ?? ' '}
        {...register('address.line1')}
      />
      <TextField
        label="Address line 2 (optional)"
        fullWidth
        error={Boolean(errors.address?.line2)}
        helperText={errors.address?.line2?.message ?? ' '}
        {...register('address.line2')}
      />
      <Stack direction="row" spacing={2}>
        <TextField
          label="City"
          fullWidth
          error={Boolean(errors.address?.city)}
          helperText={errors.address?.city?.message ?? ' '}
          {...register('address.city')}
        />
        <TextField
          label="PIN code"
          fullWidth
          error={Boolean(errors.address?.postalCode)}
          helperText={errors.address?.postalCode?.message ?? ' '}
          inputProps={{ inputMode: 'numeric', maxLength: 6 }}
          {...register('address.postalCode')}
        />
      </Stack>
    </Stack>
  );
}
