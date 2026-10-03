import { useMemo, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import {
  Alert,
  Autocomplete,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Grid,
  Link,
  MenuItem,
  Stack,
  TextField,
  Typography,
  type TextFieldProps,
  useMediaQuery,
  type Theme,
} from '@mui/material';
import MyLocationRoundedIcon from '@mui/icons-material/MyLocationRounded';
import OpenInNewRoundedIcon from '@mui/icons-material/OpenInNewRounded';
import type { CategorySummary } from '@features/categories/api';
import type { TenantPayload } from './api';
import { useSaveTenant } from './hooks';
import { mapLink, normaliseMobile, tenantErrorMessage, type Tenant } from './model';
import { tenantSchema, type TenantFormValues } from './schemas';

interface TenantFormDialogProps {
  /** The Tenant to edit; absent to create one. */
  tenant?: Tenant;
  categories: readonly CategorySummary[];
  onClose: () => void;
  onSaved: (tenant: Tenant) => void;
}

/** Decimal places kept from the browser's position: ~0.1 m, more than enough. */
const COORDINATE_DECIMALS = 6;

/**
 * Create or edit a Tenant (Requirement MT-12.2): name, contact, Service_Area
 * (base location as latitude/longitude plus a radius) and the categories it
 * covers, chosen from the active catalog. Status is set on edit only; a new
 * Tenant starts ACTIVE server-side.
 *
 * The base location is typed as coordinates rather than picked on a map: the
 * portal carries no map library, and an agency's office coordinates are one
 * search away. "Use my location" fills them from the browser when the admin is
 * onboarding the agency on site, and "Check on map" opens the point in Google
 * Maps so a swapped latitude/longitude is caught before saving.
 */
export function TenantFormDialog({ tenant, categories, onClose, onSaved }: TenantFormDialogProps) {
  // Phones get the whole screen: a dialog this dense is unusable as a 390 px card.
  const phone = useMediaQuery((theme: Theme) => theme.breakpoints.down('sm'));
  const save = useSaveTenant();
  const [locating, setLocating] = useState(false);
  const [locationError, setLocationError] = useState<string | null>(null);

  const activeIds = useMemo(
    () => new Set(categories.filter((c) => c.status === 'ACTIVE').map((c) => c.id)),
    [categories],
  );
  const schema = useMemo(() => tenantSchema(activeIds), [activeIds]);
  const categoryById = useMemo(() => new Map(categories.map((c) => [c.id, c])), [categories]);

  const {
    control,
    register,
    handleSubmit,
    setValue,
    watch,
    formState: { errors },
  } = useForm<TenantFormValues>({
    resolver: zodResolver(schema),
    mode: 'onBlur',
    defaultValues: {
      name: tenant?.name ?? '',
      contactPhone: tenant?.contactPhone ?? '',
      contactEmail: tenant?.contactEmail ?? '',
      baseLatitude: tenant ? String(tenant.baseLatitude) : '',
      baseLongitude: tenant ? String(tenant.baseLongitude) : '',
      serviceRadiusKm: tenant ? String(tenant.serviceRadiusKm) : '10',
      categoryIds: tenant?.categoryIds ?? [],
      status: tenant?.status ?? 'ACTIVE',
    },
  });

  const latitude = Number(watch('baseLatitude'));
  const longitude = Number(watch('baseLongitude'));
  const selectedIds = watch('categoryIds');
  const hasPoint =
    watch('baseLatitude').trim() !== '' &&
    watch('baseLongitude').trim() !== '' &&
    Math.abs(latitude) <= 90 &&
    Math.abs(longitude) <= 180;

  /**
   * Active categories, plus any inactive one the Tenant already has: dropping
   * it silently from the picker would make the save look like it removed
   * nothing while it did. It stays visible, marked, for the admin to remove.
   */
  const options = useMemo(
    () =>
      categories
        .filter((c) => c.status === 'ACTIVE' || selectedIds.includes(c.id))
        .map((c) => c.id),
    [categories, selectedIds],
  );

  const fillFromMyLocation = () => {
    if (!('geolocation' in navigator)) {
      setLocationError('This browser cannot share its location. Type the coordinates instead.');
      return;
    }
    setLocating(true);
    setLocationError(null);
    navigator.geolocation.getCurrentPosition(
      (position) => {
        setLocating(false);
        const setOptions = { shouldValidate: true, shouldDirty: true } as const;
        setValue('baseLatitude', position.coords.latitude.toFixed(COORDINATE_DECIMALS), setOptions);
        setValue(
          'baseLongitude',
          position.coords.longitude.toFixed(COORDINATE_DECIMALS),
          setOptions,
        );
      },
      (error) => {
        setLocating(false);
        setLocationError(
          error.code === error.PERMISSION_DENIED
            ? 'Location permission was denied. Type the coordinates instead.'
            : 'Could not read your location. Type the coordinates instead.',
        );
      },
      { enableHighAccuracy: true, timeout: 10_000 },
    );
  };

  const onSubmit = (values: TenantFormValues) => {
    const contactPhone = normaliseMobile(values.contactPhone);
    const payload: TenantPayload = {
      name: values.name.trim(),
      baseLatitude: Number(values.baseLatitude),
      baseLongitude: Number(values.baseLongitude),
      serviceRadiusKm: Number(values.serviceRadiusKm),
      categoryIds: values.categoryIds,
      ...(contactPhone ? { contactPhone } : {}),
      ...(values.contactEmail ? { contactEmail: values.contactEmail.trim() } : {}),
    };
    save.mutate(
      tenant ? { id: tenant.id, payload: { ...payload, status: values.status } } : { payload },
      { onSuccess: onSaved },
    );
  };

  return (
    <Dialog
      open
      onClose={save.isPending ? undefined : onClose}
      maxWidth="md"
      fullWidth
      fullScreen={phone}
    >
      <DialogTitle>{tenant ? `Edit ${tenant.name}` : 'New tenant'}</DialogTitle>
      <form
        noValidate
        onSubmit={(event) => {
          void handleSubmit(onSubmit)(event);
        }}
      >
        <DialogContent dividers>
          <Grid container spacing={2}>
            <Grid item xs={12} sm={tenant ? 8 : 12}>
              <TextField
                label="Agency name"
                fullWidth
                required
                {...register('name')}
                error={Boolean(errors.name)}
                helperText={errors.name?.message}
              />
            </Grid>
            {tenant ? (
              <Grid item xs={12} sm={4}>
                <Controller
                  control={control}
                  name="status"
                  render={({ field }) => (
                    <TextField
                      label="Status"
                      select
                      fullWidth
                      {...field}
                      helperText={
                        field.value === 'SUSPENDED'
                          ? 'Receives no requests; its admins are locked out.'
                          : 'Receives requests in its area.'
                      }
                    >
                      <MenuItem value="ACTIVE">Active</MenuItem>
                      <MenuItem value="SUSPENDED">Suspended</MenuItem>
                    </TextField>
                  )}
                />
              </Grid>
            ) : null}
            <Grid item xs={12} sm={6}>
              <TextField
                label="Contact phone"
                fullWidth
                inputMode="tel"
                {...register('contactPhone')}
                error={Boolean(errors.contactPhone)}
                helperText={errors.contactPhone?.message ?? 'Optional'}
              />
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField
                label="Contact email"
                type="email"
                fullWidth
                {...register('contactEmail')}
                error={Boolean(errors.contactEmail)}
                helperText={errors.contactEmail?.message ?? 'Optional'}
              />
            </Grid>

            <Grid item xs={12}>
              <Typography variant="subtitle1">Service area</Typography>
              <Typography variant="body2" color="text.secondary">
                A circle around the agency&apos;s base. Bookings inside it, in the categories below,
                are offered to the agency when no provider accepts them automatically.
              </Typography>
            </Grid>
            <Grid item xs={12} sm={4}>
              <TextField
                label="Base latitude"
                fullWidth
                required
                inputMode="decimal"
                placeholder="12.9716"
                {...register('baseLatitude')}
                error={Boolean(errors.baseLatitude)}
                helperText={errors.baseLatitude?.message ?? '−90 to 90'}
              />
            </Grid>
            <Grid item xs={12} sm={4}>
              <TextField
                label="Base longitude"
                fullWidth
                required
                inputMode="decimal"
                placeholder="77.5946"
                {...register('baseLongitude')}
                error={Boolean(errors.baseLongitude)}
                helperText={errors.baseLongitude?.message ?? '−180 to 180'}
              />
            </Grid>
            <Grid item xs={12} sm={4}>
              <TextField
                label="Radius (km)"
                fullWidth
                required
                inputMode="decimal"
                {...register('serviceRadiusKm')}
                error={Boolean(errors.serviceRadiusKm)}
                helperText={errors.serviceRadiusKm?.message ?? '1 to 100 km'}
              />
            </Grid>
            <Grid item xs={12}>
              <Stack direction="row" spacing={2} alignItems="center" flexWrap="wrap" useFlexGap>
                <Button
                  size="small"
                  variant="outlined"
                  startIcon={<MyLocationRoundedIcon />}
                  onClick={fillFromMyLocation}
                  disabled={locating}
                >
                  {locating ? 'Locating…' : 'Use my location'}
                </Button>
                {hasPoint ? (
                  <Link
                    href={mapLink(latitude, longitude)}
                    target="_blank"
                    rel="noopener noreferrer"
                    variant="body2"
                    sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5 }}
                  >
                    Check on map <OpenInNewRoundedIcon sx={{ fontSize: 14 }} />
                  </Link>
                ) : null}
              </Stack>
              {locationError ? (
                <Alert severity="warning" sx={{ mt: 1 }}>
                  {locationError}
                </Alert>
              ) : null}
            </Grid>

            <Grid item xs={12}>
              <Controller
                control={control}
                name="categoryIds"
                render={({ field }) => (
                  <Autocomplete
                    multiple
                    disableCloseOnSelect
                    options={options}
                    value={field.value}
                    onChange={(_, value) => field.onChange(value)}
                    onBlur={field.onBlur}
                    getOptionLabel={(id) => categoryById.get(id)?.name ?? id}
                    renderTags={(value, getTagProps) =>
                      value.map((id, index) => {
                        const inactive = !activeIds.has(id);
                        const { key, ...tagProps } = getTagProps({ index });
                        return (
                          <Chip
                            key={key}
                            size="small"
                            {...tagProps}
                            color={inactive ? 'warning' : 'default'}
                            label={`${categoryById.get(id)?.name ?? id}${inactive ? ' (inactive)' : ''}`}
                          />
                        );
                      })
                    }
                    renderInput={(params) => (
                      // MUI types the render params with optional props that
                      // exactOptionalPropertyTypes refuses to spread as-is.
                      <TextField
                        {...(params as TextFieldProps)}
                        label="Categories covered"
                        required
                        error={Boolean(errors.categoryIds)}
                        helperText={
                          errors.categoryIds?.message ?? 'Only active catalog categories.'
                        }
                      />
                    )}
                  />
                )}
              />
            </Grid>
          </Grid>
          {save.isError ? (
            <Alert severity="error" sx={{ mt: 2 }}>
              {tenantErrorMessage(save.error)}
            </Alert>
          ) : null}
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose} disabled={save.isPending}>
            Cancel
          </Button>
          <Button type="submit" variant="contained" disabled={save.isPending}>
            {save.isPending ? 'Saving…' : tenant ? 'Save changes' : 'Create tenant'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
