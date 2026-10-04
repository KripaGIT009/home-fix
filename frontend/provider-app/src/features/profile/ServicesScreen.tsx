import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Checkbox,
  Chip,
  FormControlLabel,
  FormGroup,
  FormHelperText,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import HandymanRoundedIcon from '@mui/icons-material/HandymanRounded';
import { isApiError } from '@api/client';
import { AppShell } from '@components/AppShell';
import { QueryStateView } from '@components/QueryStateView';
import { useAuthStore } from '@stores/authStore';
import type { CatalogCategory, ProviderProfile } from './api';
import { hasBaseLocation, PROFILE_STEP_ROUTES } from './completeness';
import { useMyProfile, useSaveProfile, useServiceCatalog } from './hooks';
import {
  buildServicesSchema,
  deriveSelection,
  PROFILE_LIMITS,
  servicesFormDefaults,
  toProfilePayload,
  type ServicesFormValues,
} from './schemas';

/**
 * Services & skills (Requirement 4.1): the name customers see, years of
 * experience, and the services the provider offers, picked from the active
 * catalog. The skill tags dispatch matches on are derived from the chosen
 * services, so the provider never has to guess tag spellings.
 *
 * Saving creates the profile the first time, after which the service area
 * and availability screens can be used.
 */
export function ServicesScreen() {
  const navigate = useNavigate();
  const profile = useMyProfile();
  const catalog = useServiceCatalog();

  return (
    <AppShell title="Services & skills" onBack={() => navigate('/profile')}>
      <QueryStateView
        isLoading={profile.isLoading || catalog.isLoading}
        isError={profile.isError || catalog.isError}
        error={profile.error ?? catalog.error}
        onRetry={() => {
          void profile.refetch();
          void catalog.refetch();
        }}
        isEmpty={catalog.data?.length === 0}
        emptyMessage="No services are open for providers yet. Please check back later."
      >
        {catalog.data && profile.data !== undefined ? (
          <ServicesForm
            profile={profile.data}
            catalog={catalog.data}
            onSaved={(saved) =>
              navigate(hasBaseLocation(saved) ? '/profile' : PROFILE_STEP_ROUTES.serviceArea)
            }
          />
        ) : null}
      </QueryStateView>
    </AppShell>
  );
}

interface ServicesFormProps {
  profile: ProviderProfile | null;
  catalog: CatalogCategory[];
  onSaved: (profile: ProviderProfile) => void;
}

/** The form itself, mounted once both the profile and the catalog are loaded. */
export function ServicesForm({ profile, catalog, onSaved }: ServicesFormProps) {
  const accountName = useAuthStore((state) => state.user?.displayName) ?? '';
  const save = useSaveProfile();
  const catalogQuery = useServiceCatalog();

  const schema = useMemo(() => buildServicesSchema(catalog), [catalog]);
  // The starting point is fixed at mount: a later catalog refresh must not
  // rewrite what the provider is editing.
  const [defaults] = useState(() => servicesFormDefaults(profile, catalog, accountName));

  const {
    control,
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<ServicesFormValues>({
    resolver: zodResolver(schema),
    defaultValues: defaults.values,
    mode: 'onSubmit',
  });

  const chosen = useWatch({ control, name: 'subcategoryIds' });
  const { skillTags } = useMemo(() => deriveSelection(chosen, catalog), [chosen, catalog]);

  const onSubmit = (values: ServicesFormValues) => {
    save.mutate(toProfilePayload(values, catalog, profile), {
      onSuccess: onSaved,
      onError: (error) => {
        // A service was switched off since the list was loaded: show the
        // current catalog so the provider can see what is still offered.
        if (error.code === 'CATEGORY_DEACTIVATED' || error.code === 'SUBCATEGORY_DEACTIVATED') {
          void catalogQuery.refetch();
        }
      },
    });
  };

  return (
    <Stack
      component="form"
      spacing={2}
      noValidate
      onSubmit={(event) => void handleSubmit(onSubmit)(event)}
    >
      {!profile ? (
        <Alert severity="info">
          Tell us what work you do. HomeFix offers you jobs for the services you pick here.
        </Alert>
      ) : null}
      {defaults.droppedCount > 0 ? (
        <Alert severity="warning">
          {defaults.droppedCount === 1
            ? 'One service on your profile is no longer offered and has been removed.'
            : `${defaults.droppedCount} services on your profile are no longer offered and have been removed.`}{' '}
          Save to update your profile.
        </Alert>
      ) : null}

      <Card>
        <CardContent>
          <Stack spacing={1.5}>
            <Typography variant="subtitle1" fontWeight={700} component="h2">
              About you
            </Typography>
            <TextField
              label="Name shown to customers"
              fullWidth
              size="small"
              autoComplete="name"
              inputProps={{ maxLength: PROFILE_LIMITS.displayNameMax }}
              error={Boolean(errors.displayName)}
              helperText={errors.displayName?.message ?? 'Optional'}
              {...register('displayName')}
            />
            <TextField
              label="Years of experience"
              fullWidth
              size="small"
              autoComplete="off"
              inputProps={{ inputMode: 'numeric', maxLength: 2 }}
              error={Boolean(errors.yearsExperience)}
              helperText={
                errors.yearsExperience?.message ??
                `Whole years in this trade, ${PROFILE_LIMITS.minYearsExperience} to ${PROFILE_LIMITS.maxYearsExperience}`
              }
              {...register('yearsExperience')}
            />
          </Stack>
        </CardContent>
      </Card>

      <Card>
        <CardContent>
          <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 0.5 }}>
            <HandymanRoundedIcon color="primary" aria-hidden />
            <Typography variant="subtitle1" fontWeight={700} component="h2">
              Services you offer
            </Typography>
          </Stack>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
            Pick up to {PROFILE_LIMITS.maxCategories} categories and up to{' '}
            {PROFILE_LIMITS.maxSubcategoriesPerCategory} services in each.
          </Typography>

          <Controller
            control={control}
            name="subcategoryIds"
            render={({ field }) => {
              const selected = new Set(field.value);
              const toggle = (id: string, on: boolean) =>
                field.onChange(
                  on ? [...field.value, id] : field.value.filter((value) => value !== id),
                );
              return (
                <Stack spacing={2}>
                  {catalog.map((category) => {
                    const count = category.subcategories.filter((sub) =>
                      selected.has(sub.id),
                    ).length;
                    const headingId = `category-${category.id}`;
                    return (
                      <Box key={category.id}>
                        <Stack direction="row" spacing={1} alignItems="center">
                          <Typography
                            id={headingId}
                            variant="subtitle2"
                            fontWeight={700}
                            component="h3"
                          >
                            {category.name}
                          </Typography>
                          {count > 0 ? (
                            <Chip size="small" color="primary" label={`${count} selected`} />
                          ) : null}
                        </Stack>
                        <FormGroup aria-labelledby={headingId}>
                          {category.subcategories.map((sub) => (
                            <FormControlLabel
                              key={sub.id}
                              label={sub.name}
                              control={
                                <Checkbox
                                  size="small"
                                  checked={selected.has(sub.id)}
                                  onChange={(event) => toggle(sub.id, event.target.checked)}
                                  onBlur={field.onBlur}
                                />
                              }
                            />
                          ))}
                        </FormGroup>
                      </Box>
                    );
                  })}
                </Stack>
              );
            }}
          />
          {errors.subcategoryIds ? (
            <FormHelperText error role="alert" sx={{ mt: 1 }}>
              {errors.subcategoryIds.message}
            </FormHelperText>
          ) : null}
        </CardContent>
      </Card>

      <Card>
        <CardContent>
          <Typography variant="subtitle1" fontWeight={700} component="h2">
            Your skills
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
            Jobs are matched on these skills. They come from the services you pick.
          </Typography>
          {skillTags.length > 0 ? (
            <Stack direction="row" spacing={0.75} flexWrap="wrap" useFlexGap>
              {skillTags.map((tag) => (
                <Chip key={tag} size="small" variant="outlined" label={tag} />
              ))}
            </Stack>
          ) : (
            <Typography variant="body2" color="text.secondary">
              Pick a service to see its skills.
            </Typography>
          )}
        </CardContent>
      </Card>

      {save.isError ? (
        <Alert severity="error">
          {save.error.code === 'CATEGORY_DEACTIVATED' ||
          save.error.code === 'SUBCATEGORY_DEACTIVATED'
            ? 'One of the services you picked is no longer offered. Please review your choice and save again.'
            : isApiError(save.error)
              ? save.error.message
              : 'Could not save your services.'}
        </Alert>
      ) : null}

      <Button type="submit" variant="contained" size="large" disabled={save.isPending}>
        {save.isPending ? 'Saving…' : profile ? 'Save services' : 'Save and continue'}
      </Button>
    </Stack>
  );
}
