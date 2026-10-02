import { useCallback, useState } from 'react';
import type { UseFormRegister, FieldErrors, UseFormSetValue } from 'react-hook-form';
import { Alert, Button, Chip, Stack, TextField, Typography } from '@mui/material';
import MyLocationRoundedIcon from '@mui/icons-material/MyLocationRounded';
import HomeRoundedIcon from '@mui/icons-material/HomeRounded';
import { useAuthStore } from '@stores/authStore';
import type { ServiceRequestFormValues } from './schemas';
import { useSavedAddressStore, type SavedAddress } from './savedAddresses';

interface AddressFieldsProps {
  register: UseFormRegister<ServiceRequestFormValues>;
  errors: FieldErrors<ServiceRequestFormValues>;
  setValue: UseFormSetValue<ServiceRequestFormValues>;
}

/**
 * Address input for the Service Request screen (Requirement 7.1). Supports
 * GPS detection (Geolocation API) that stores coordinates, picking an address
 * this device saved on an earlier booking, and entry of the address lines,
 * city, and PIN code.
 *
 * Coordinates are required: dispatch locates the job only from a saved address,
 * and the Customer Service cannot save one without them (there is no forward
 * geocoder). Typed lines alone describe the place to the pro but do not locate
 * it, so the form asks for GPS or a saved address until it has coordinates.
 */
export function AddressFields({ register, errors, setValue }: AddressFieldsProps) {
  const userId = useAuthStore((state) => state.user?.id);
  // Select the stored array itself (not a defaulted copy) so the reference is
  // stable between renders.
  const savedAddresses = useSavedAddressStore((state) =>
    userId ? state.byUser[userId] : undefined,
  );
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
        setValue('address.longitude', longitude, { shouldDirty: true });
        // Validated last, once both coordinates are in, so a "location needed"
        // error from an earlier submit clears.
        setValue('address.latitude', latitude, { shouldDirty: true, shouldValidate: true });
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

  const handlePickSaved = useCallback(
    (saved: SavedAddress) => {
      setValue('address', saved.address, { shouldDirty: true, shouldValidate: true });
      setDetectMessage({ severity: 'success', text: 'Saved address selected.' });
    },
    [setValue],
  );

  const locationError = errors.address?.latitude?.message ?? errors.address?.longitude?.message;

  return (
    <Stack spacing={2}>
      <Stack direction="row" justifyContent="space-between" alignItems="center">
        <Typography variant="subtitle2">Service address</Typography>
        <Button
          type="button"
          size="small"
          variant="outlined"
          color="primary"
          startIcon={<MyLocationRoundedIcon />}
          onClick={handleDetect}
          disabled={detecting}
        >
          {detecting ? 'Detecting…' : 'Use my location'}
        </Button>
      </Stack>

      {savedAddresses && savedAddresses.length > 0 ? (
        <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
          {savedAddresses.map((saved) => (
            <Chip
              key={saved.addressId}
              icon={<HomeRoundedIcon />}
              label={[saved.address.line1, saved.address.city].join(', ')}
              onClick={() => handlePickSaved(saved)}
              variant="outlined"
            />
          ))}
        </Stack>
      ) : null}

      {locationError ? (
        <Alert severity="error" role="alert">
          {locationError}
        </Alert>
      ) : null}

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
