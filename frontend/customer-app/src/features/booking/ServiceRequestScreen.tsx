import { useCallback, useMemo, useState, type ReactNode } from 'react';
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
import CheckCircleRoundedIcon from '@mui/icons-material/CheckCircleRounded';
import { AppShell } from '@components/AppShell';
import { QueryStateView } from '@components/QueryStateView';
import { IconTile } from '@components/StateViews';
import { useSubcategory } from '@features/catalog/hooks';
import { formatDuration, formatPrice } from '@lib/format';
import { brand, radius, shadows } from '@lib/theme';
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
 * The form is three numbered questions — where, when, what. On desktop the
 * service summary and the submit button sit in a sticky aside; on mobile the
 * button lives in a sticky bar at the bottom of the screen.
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

      // The catalog entry is what knows the parent category, and the Booking
      // Service rejects a creation without it. The form only renders once the
      // subcategory query has resolved, so this is defensive rather than a
      // state the user can reach.
      if (!subcategory) return;

      setDraft({
        categoryId: subcategory.categoryId,
        subcategoryId,
        isEmergency: values.isEmergency,
        ...(scheduledAt ? { scheduledAt } : {}),
        address: values.address,
        ...(values.description ? { description: values.description } : {}),
        media,
      });

      navigate(`/book/${subcategoryId}/estimate`);
    },
    [media, navigate, setDraft, subcategory, subcategoryId],
  );

  return (
    <AppShell title="Book a service" width="full">
      <QueryStateView
        isLoading={subcategoryQuery.isLoading}
        isError={subcategoryQuery.isError}
        error={subcategoryQuery.error}
        onRetry={() => void subcategoryQuery.refetch()}
        skeletonHeight={160}
      >
        <Box
          component="form"
          onSubmit={(event) => void handleSubmit(onSubmit)(event)}
          noValidate
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: '1fr', md: 'minmax(0, 1fr) 340px' },
            gap: { xs: 2, md: 3 },
            alignItems: 'start',
          }}
        >
          <Stack spacing={2} sx={{ minWidth: 0 }}>
            {/* On mobile the summary leads the form; on desktop it is the aside. */}
            <Box sx={{ display: { xs: 'block', md: 'none' } }}>
              <ServiceSummary
                name={subcategory?.name}
                price={subcategory?.basePrice}
                duration={subcategory?.estimatedDurationMin}
              />
            </Box>

            <FormSection step={1} icon={<PlaceRoundedIcon />} title="Where should we come?">
              <AddressFields register={register} errors={errors} setValue={setValue} />
            </FormSection>

            <FormSection step={2} icon={<EventRoundedIcon />} title="When do you need it?">
              <Stack spacing={2}>
                {emergencyAvailable ? (
                  <Box
                    sx={{
                      px: 2,
                      py: 1,
                      borderRadius: `${radius.md}px`,
                      border: 1,
                      borderColor: isEmergency ? 'error.main' : 'divider',
                      bgcolor: isEmergency ? brand.redSoft : 'transparent',
                      transition: 'background-color .2s, border-color .2s',
                    }}
                  >
                    <FormControlLabel
                      sx={{ m: 0, width: '100%' }}
                      control={
                        <Switch color="error" checked={isEmergency} {...register('isEmergency')} />
                      }
                      label={
                        <Box>
                          <Stack direction="row" spacing={0.75} alignItems="center">
                            <BoltRoundedIcon fontSize="small" color="error" />
                            <Typography variant="subtitle2" fontWeight={700}>
                              Emergency — send a pro now
                            </Typography>
                          </Stack>
                          <Typography variant="caption" color="text.secondary">
                            Available 24×7. An emergency charge applies.
                          </Typography>
                        </Box>
                      }
                    />
                  </Box>
                ) : null}

                {isEmergency ? (
                  <Typography variant="body2" color="text.secondary">
                    We&apos;ll find the nearest verified professional as soon as you confirm.
                  </Typography>
                ) : (
                  <TextField
                    label="Preferred date & time"
                    type="datetime-local"
                    fullWidth
                    InputLabelProps={{ shrink: true }}
                    inputProps={{ min: scheduleBounds.min, max: scheduleBounds.max }}
                    error={Boolean(errors.scheduledAt)}
                    helperText={
                      errors.scheduledAt?.message ??
                      'At least 2 hours from now, up to 90 days ahead.'
                    }
                    {...register('scheduledAt')}
                  />
                )}
              </Stack>
            </FormSection>

            <FormSection step={3} icon={<NotesRoundedIcon />} title="What's the problem?">
              <Stack spacing={2.5}>
                <TextField
                  label="Describe the job (optional)"
                  placeholder="e.g. Kitchen tap is leaking from the base"
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
          </Stack>

          <Box
            sx={{
              position: 'sticky',
              bottom: { xs: 0, md: 'auto' },
              top: { md: 96 },
              zIndex: 2,
              mx: { xs: -2, sm: -3, md: 0 },
            }}
          >
            <Box sx={{ display: { xs: 'none', md: 'block' }, mb: 2 }}>
              <ServiceSummary
                name={subcategory?.name}
                price={subcategory?.basePrice}
                duration={subcategory?.estimatedDurationMin}
                detailed
              />
            </Box>
            <Box
              sx={{
                p: { xs: 2, md: 0 },
                pb: { xs: 'calc(16px + env(safe-area-inset-bottom))', md: 0 },
                bgcolor: { xs: 'background.paper', md: 'transparent' },
                borderTop: { xs: `1px solid ${brand.line}`, md: 'none' },
                boxShadow: { xs: shadows.bar, md: 'none' },
              }}
            >
              <Button type="submit" variant="contained" size="large" fullWidth>
                See price estimate
              </Button>
              <Typography
                variant="caption"
                color="text.secondary"
                display="block"
                textAlign="center"
                sx={{ mt: 1 }}
              >
                You won&apos;t be charged yet.
              </Typography>
            </Box>
          </Box>
        </Box>
      </QueryStateView>
    </AppShell>
  );
}

/** The service being booked, with its "from" price and typical duration. */
function ServiceSummary({
  name,
  price,
  duration,
  detailed = false,
}: {
  name: string | undefined;
  price: number | undefined;
  duration: number | undefined;
  detailed?: boolean;
}) {
  return (
    <Card sx={{ bgcolor: brand.accentSoft, borderColor: brand.accentLine }}>
      <CardContent>
        <Typography variant="overline" color="primary.dark">
          You&apos;re booking
        </Typography>
        <Typography variant="h5" component="p">
          {name}
        </Typography>
        <Stack direction="row" spacing={3} sx={{ mt: 1.5 }}>
          {price !== undefined ? (
            <Box>
              <Typography variant="caption" color="text.secondary">
                Starts at
              </Typography>
              <Typography variant="h5" component="p" fontWeight={800}>
                {formatPrice(price)}
              </Typography>
            </Box>
          ) : null}
          {duration ? (
            <Box>
              <Typography variant="caption" color="text.secondary">
                Typical visit
              </Typography>
              <Typography variant="h5" component="p" fontWeight={800}>
                {formatDuration(duration)}
              </Typography>
            </Box>
          ) : null}
        </Stack>
        {detailed ? (
          <Stack spacing={1} sx={{ mt: 2.5, pt: 2, borderTop: `1px solid ${brand.accentLine}` }}>
            {[
              'An itemised estimate before you confirm',
              'A verified, background-checked pro',
              'Live tracking and in-app chat',
            ].map((point) => (
              <Stack key={point} direction="row" spacing={1} alignItems="center">
                <CheckCircleRoundedIcon sx={{ fontSize: 18, color: 'success.main' }} aria-hidden />
                <Typography variant="body2">{point}</Typography>
              </Stack>
            ))}
          </Stack>
        ) : null}
      </CardContent>
    </Card>
  );
}

/** One numbered block of the request form. */
function FormSection({
  step,
  icon,
  title,
  children,
}: {
  step: number;
  icon: ReactNode;
  title: string;
  children: ReactNode;
}) {
  return (
    <Card component="section" aria-label={title}>
      <CardContent>
        <Stack direction="row" spacing={1.5} alignItems="center" sx={{ mb: 2.5 }}>
          <IconTile size={36}>{icon}</IconTile>
          <Box>
            <Typography variant="caption" color="text.secondary" fontWeight={600}>
              Step {step} of 3
            </Typography>
            <Typography variant="h6" component="h2" sx={{ lineHeight: 1.2 }}>
              {title}
            </Typography>
          </Box>
        </Stack>
        {children}
      </CardContent>
    </Card>
  );
}
