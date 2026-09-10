import { useCallback, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import {
  Box,
  Button,
  Card,
  CardContent,
  FormControlLabel,
  Stack,
  Switch,
  TextField,
  Typography,
} from '@mui/material';
import BoltRoundedIcon from '@mui/icons-material/BoltRounded';
import PlaceRoundedIcon from '@mui/icons-material/PlaceRounded';
import EventRoundedIcon from '@mui/icons-material/EventRounded';
import NotesRoundedIcon from '@mui/icons-material/NotesRounded';
import { AppShell } from '@components/AppShell';
import { QueryStateView } from '@components/QueryStateView';
import { useSubcategory } from '@features/catalog/hooks';
import { formatCurrency } from '@lib/format';
import { brand } from '@lib/theme';
import { MAX_DESCRIPTION_LENGTH } from './constants';
import { maxScheduleValue, minScheduleValue, toIsoString } from './datetime';
import { AddressFields } from './AddressFields';
import { MediaUpload } from './MediaUpload';
import { useBookingDraftStore } from './draftStore';
import { serviceRequestSchema, type ServiceRequestFormValues } from './schemas';

/**
 * Service Request screen (Requirement 7). Collects the service address (GPS or
 * manual), a scheduled date/time (2 h lead time, ≤ 90 days), an optional
 * description, media (validated), and an emergency toggle. On submit the draft
 * is stored and the flow proceeds to the Price Estimate screen (Requirement
 * 7.3 — an itemized estimate is shown before confirmation).
 *
 * The form is grouped into one card per question — where, when, what — so a
 * long mobile form reads as a short sequence of decisions.
 */
export function ServiceRequestScreen() {
  const { subcategoryId = '' } = useParams();
  const navigate = useNavigate();
  const setDraft = useBookingDraftStore((state) => state.setDraft);

  const subcategoryQuery = useSubcategory(subcategoryId);
  const subcategory = subcategoryQuery.data;

  const [media, setMedia] = useState<File[]>([]);

  // Compute the picker bounds once per mount.
  const scheduleBounds = useMemo(() => ({ min: minScheduleValue(), max: maxScheduleValue() }), []);

  const {
    register,
    handleSubmit,
    setValue,
    watch,
    formState: { errors },
  } = useForm<ServiceRequestFormValues>({
    resolver: zodResolver(serviceRequestSchema),
    mode: 'onBlur',
    defaultValues: {
      address: { line1: '', line2: '', city: '', postalCode: '' },
      scheduledAt: '',
      description: '',
      isEmergency: false,
    },
  });

  const isEmergency = watch('isEmergency');
  const emergencyAvailable = subcategory?.emergencyAvailable ?? false;

  const onSubmit = useCallback(
    (values: ServiceRequestFormValues) => {
      // Emergency bookings dispatch immediately, so no scheduled time is sent
      // (Requirement 8.1). Scheduled bookings send the ISO time (Requirement 7.1).
      const scheduledAt = values.isEmergency ? undefined : toIsoString(values.scheduledAt);

      setDraft({
        subcategoryId,
        isEmergency: values.isEmergency,
        ...(scheduledAt ? { scheduledAt } : {}),
        address: values.address,
        ...(values.description ? { description: values.description } : {}),
        media,
      });

      navigate(`/book/${subcategoryId}/estimate`);
    },
    [media, navigate, setDraft, subcategoryId],
  );

  return (
    <AppShell title="Service request">
      <QueryStateView
        isLoading={subcategoryQuery.isLoading}
        isError={subcategoryQuery.isError}
        error={subcategoryQuery.error}
        onRetry={() => void subcategoryQuery.refetch()}
      >
        <Stack
          component="form"
          spacing={2}
          onSubmit={(event) => void handleSubmit(onSubmit)(event)}
          noValidate
        >
          <Card sx={{ borderColor: 'transparent', bgcolor: brand.accentSoft }}>
            <CardContent>
              <Stack direction="row" justifyContent="space-between" alignItems="flex-start">
                <Box>
                  <Typography variant="h6" component="h1">
                    {subcategory?.name}
                  </Typography>
                  <Typography variant="body2" color="text.secondary">
                    Tell us where and when, and add photos to help the pro prepare.
                  </Typography>
                </Box>
                {subcategory ? (
                  <Stack alignItems="flex-end" sx={{ flexShrink: 0, pl: 1 }}>
                    <Typography variant="caption" color="text.secondary">
                      From
                    </Typography>
                    <Typography variant="subtitle1" fontWeight={700}>
                      {formatCurrency(subcategory.basePrice)}
                    </Typography>
                  </Stack>
                ) : null}
              </Stack>
            </CardContent>
          </Card>

          <FormSection icon={<PlaceRoundedIcon />} title="Service location">
            <AddressFields register={register} errors={errors} setValue={setValue} />
          </FormSection>

          <FormSection icon={<EventRoundedIcon />} title="When do you need it?">
            <Stack spacing={1.5}>
              {emergencyAvailable ? (
                <Box
                  sx={{
                    px: 1.5,
                    py: 0.5,
                    borderRadius: 2,
                    border: 1,
                    borderColor: isEmergency ? 'error.main' : 'divider',
                    bgcolor: isEmergency ? brand.redSoft : 'transparent',
                    transition: 'background-color .2s, border-color .2s',
                  }}
                >
                  <FormControlLabel
                    control={
                      <Switch color="error" checked={isEmergency} {...register('isEmergency')} />
                    }
                    label={
                      <Stack direction="row" spacing={0.75} alignItems="center">
                        <BoltRoundedIcon fontSize="small" color="error" />
                        <Typography variant="body2" fontWeight={600}>
                          Emergency — dispatch a pro now
                        </Typography>
                      </Stack>
                    }
                  />
                </Box>
              ) : null}

              {isEmergency ? (
                <Typography variant="body2" color="text.secondary">
                  We&apos;ll find the nearest verified professional right away.
                </Typography>
              ) : (
                <TextField
                  label="Preferred date &amp; time"
                  type="datetime-local"
                  fullWidth
                  InputLabelProps={{ shrink: true }}
                  inputProps={{ min: scheduleBounds.min, max: scheduleBounds.max }}
                  error={Boolean(errors.scheduledAt)}
                  helperText={
                    errors.scheduledAt?.message ?? 'At least 2 hours from now, up to 90 days ahead.'
                  }
                  {...register('scheduledAt')}
                />
              )}
            </Stack>
          </FormSection>

          <FormSection icon={<NotesRoundedIcon />} title="What's the problem?">
            <Stack spacing={2}>
              <TextField
                label="Describe the job (optional)"
                multiline
                minRows={3}
                fullWidth
                error={Boolean(errors.description)}
                helperText={
                  errors.description?.message ?? `Up to ${MAX_DESCRIPTION_LENGTH} characters.`
                }
                inputProps={{ maxLength: MAX_DESCRIPTION_LENGTH }}
                {...register('description')}
              />
              <MediaUpload files={media} onChange={setMedia} />
            </Stack>
          </FormSection>

          <Button type="submit" variant="contained" size="large" fullWidth>
            See price estimate
          </Button>
        </Stack>
      </QueryStateView>
    </AppShell>
  );
}

/** One titled block of the request form, rendered as a card with a lead icon. */
function FormSection({
  icon,
  title,
  children,
}: {
  icon: React.ReactNode;
  title: string;
  children: React.ReactNode;
}) {
  return (
    <Card>
      <CardContent>
        <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 2 }}>
          <Box sx={{ color: 'primary.main', display: 'flex' }}>{icon}</Box>
          <Typography variant="subtitle1" fontWeight={700}>
            {title}
          </Typography>
        </Stack>
        {children}
      </CardContent>
    </Card>
  );
}
