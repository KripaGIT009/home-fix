import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Controller, useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Slider,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import MyLocationRoundedIcon from '@mui/icons-material/MyLocationRounded';
import PlaceRoundedIcon from '@mui/icons-material/PlaceRounded';
import { isApiError } from '@api/client';
import { AppShell } from '@components/AppShell';
import { QueryStateView } from '@components/QueryStateView';
import { readDevicePosition } from '@features/jobs/deviceLocation';
import type { ProviderProfile } from './api';
import { useMyProfile, useSaveServiceArea } from './hooks';
import { ProfileRequired } from './ProfileRequired';
import {
  formatCoordinate,
  PROFILE_LIMITS,
  serviceAreaFormDefaults,
  serviceAreaSchema,
  toServiceAreaChange,
  type ServiceAreaFormValues,
} from './schemas';

const RADIUS_MARKS = [1, 25, 50, 75, 100].map((value) => ({ value, label: `${value}` }));

/**
 * Service area (Requirements 4.2, 4.4, 8.2): the base location dispatch
 * measures distance from, and the radius the provider will travel. A provider
 * without a base location is never offered a job.
 *
 * The location comes from the device when it can (Capacitor geolocation on
 * the native apps, the browser API on the web), and can always be typed in by
 * hand — permission refused, no fix indoors, or a base that is not where the
 * provider happens to be standing.
 */
export function ServiceAreaScreen() {
  const navigate = useNavigate();
  const profile = useMyProfile();

  return (
    <AppShell title="Service area" onBack={() => navigate('/profile')}>
      <QueryStateView
        isLoading={profile.isLoading}
        isError={profile.isError}
        error={profile.error}
        onRetry={() => void profile.refetch()}
      >
        {profile.data === null ? <ProfileRequired what="your service area" /> : null}
        {profile.data ? (
          <ServiceAreaForm profile={profile.data} onSaved={() => navigate('/profile')} />
        ) : null}
      </QueryStateView>
    </AppShell>
  );
}

type LocateState =
  | { status: 'idle' }
  | { status: 'locating' }
  | { status: 'located'; accuracyMeters: number | null }
  | { status: 'denied' }
  | { status: 'unavailable' };

interface ServiceAreaFormProps {
  profile: ProviderProfile;
  onSaved: () => void;
}

export function ServiceAreaForm({ profile, onSaved }: ServiceAreaFormProps) {
  const save = useSaveServiceArea();
  const [locate, setLocate] = useState<LocateState>({ status: 'idle' });

  const {
    control,
    register,
    handleSubmit,
    setValue,
    formState: { errors },
  } = useForm<ServiceAreaFormValues>({
    resolver: zodResolver(serviceAreaSchema),
    defaultValues: serviceAreaFormDefaults(profile),
    mode: 'onSubmit',
  });

  const fillFromDevice = async () => {
    setLocate({ status: 'locating' });
    const reading = await readDevicePosition();
    if (reading.status !== 'ok') {
      setLocate({ status: reading.status });
      return;
    }
    const options = { shouldValidate: true, shouldDirty: true } as const;
    setValue('latitude', formatCoordinate(reading.latitude), options);
    setValue('longitude', formatCoordinate(reading.longitude), options);
    setLocate({ status: 'located', accuracyMeters: reading.accuracyMeters });
  };

  const onSubmit = (values: ServiceAreaFormValues) => {
    save.mutate(toServiceAreaChange(values, profile), { onSuccess: onSaved });
  };

  return (
    <Stack
      component="form"
      spacing={2}
      noValidate
      onSubmit={(event) => void handleSubmit(onSubmit)(event)}
    >
      {profile.baseLatitude === null ? (
        <Alert severity="warning">
          You have no base location yet, so HomeFix cannot offer you jobs. Set it below.
        </Alert>
      ) : null}

      <Card>
        <CardContent>
          <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 0.5 }}>
            <PlaceRoundedIcon color="primary" aria-hidden />
            <Typography variant="subtitle1" fontWeight={700} component="h2">
              Base location
            </Typography>
          </Stack>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
            Where you usually start from, such as your home or workshop. Distance to each job is
            measured from here.
          </Typography>

          <Button
            variant="outlined"
            fullWidth
            startIcon={<MyLocationRoundedIcon />}
            onClick={() => void fillFromDevice()}
            disabled={locate.status === 'locating'}
          >
            {locate.status === 'locating' ? 'Finding your location…' : 'Use my current location'}
          </Button>

          {locate.status === 'located' ? (
            <Alert severity="success" sx={{ mt: 1.5 }}>
              Location found
              {locate.accuracyMeters !== null
                ? ` (accurate to about ${Math.round(locate.accuracyMeters)} m)`
                : ''}
              . Save to use it as your base.
            </Alert>
          ) : null}
          {locate.status === 'denied' ? (
            <Alert severity="warning" sx={{ mt: 1.5 }}>
              Location permission is turned off for HomeFix. Allow it in your phone&apos;s settings,
              or type your coordinates below.
            </Alert>
          ) : null}
          {locate.status === 'unavailable' ? (
            <Alert severity="warning" sx={{ mt: 1.5 }}>
              Could not get your location. Check that location is switched on, or type your
              coordinates below.
            </Alert>
          ) : null}

          <Typography variant="caption" color="text.secondary" component="p" sx={{ mt: 2, mb: 1 }}>
            Or enter coordinates (from a maps app, e.g. 12.9716, 77.5946)
          </Typography>
          <Stack direction="row" spacing={1.5}>
            <TextField
              label="Latitude"
              fullWidth
              size="small"
              autoComplete="off"
              inputProps={{ inputMode: 'decimal' }}
              error={Boolean(errors.latitude)}
              helperText={errors.latitude?.message}
              {...register('latitude')}
            />
            <TextField
              label="Longitude"
              fullWidth
              size="small"
              autoComplete="off"
              inputProps={{ inputMode: 'decimal' }}
              error={Boolean(errors.longitude)}
              helperText={errors.longitude?.message}
              {...register('longitude')}
            />
          </Stack>
        </CardContent>
      </Card>

      <Card>
        <CardContent>
          <Controller
            control={control}
            name="serviceRadiusKm"
            render={({ field }) => (
              <Box>
                <Stack direction="row" justifyContent="space-between" alignItems="baseline">
                  <Typography
                    id="service-radius-label"
                    variant="subtitle1"
                    fontWeight={700}
                    component="h2"
                  >
                    Travel radius
                  </Typography>
                  <Typography variant="subtitle1" fontWeight={700} color="primary">
                    {field.value} km
                  </Typography>
                </Stack>
                <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
                  You are offered jobs up to this far from your base.
                </Typography>
                <Box sx={{ px: 1 }}>
                  <Slider
                    value={field.value}
                    min={PROFILE_LIMITS.minServiceRadiusKm}
                    max={PROFILE_LIMITS.maxServiceRadiusKm}
                    step={1}
                    marks={RADIUS_MARKS}
                    valueLabelDisplay="auto"
                    getAriaValueText={(value) => `${value} km`}
                    aria-labelledby="service-radius-label"
                    onChange={(_, value) => field.onChange(Array.isArray(value) ? value[0] : value)}
                    onBlur={field.onBlur}
                  />
                </Box>
                {errors.serviceRadiusKm ? (
                  <Typography variant="caption" color="error">
                    {errors.serviceRadiusKm.message}
                  </Typography>
                ) : null}
              </Box>
            )}
          />
        </CardContent>
      </Card>

      {save.isError ? (
        <Alert severity="error">
          {save.error.code === 'CATEGORY_DEACTIVATED' ||
          save.error.code === 'SUBCATEGORY_DEACTIVATED'
            ? 'A service on your profile is no longer offered. Update your services, then set your location again.'
            : isApiError(save.error)
              ? save.error.message
              : 'Could not save your service area.'}
        </Alert>
      ) : null}

      <Button type="submit" variant="contained" size="large" disabled={save.isPending}>
        {save.isPending ? 'Saving…' : 'Save service area'}
      </Button>
    </Stack>
  );
}
