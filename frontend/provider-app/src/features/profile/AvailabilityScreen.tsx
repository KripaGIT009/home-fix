import { useNavigate } from 'react-router-dom';
import { Controller, useFieldArray, useForm, useWatch, type Control } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Divider,
  FormControlLabel,
  FormHelperText,
  IconButton,
  Radio,
  RadioGroup,
  Stack,
  Switch,
  TextField,
  Typography,
} from '@mui/material';
import AddRoundedIcon from '@mui/icons-material/AddRounded';
import DeleteOutlineRoundedIcon from '@mui/icons-material/DeleteOutlineRounded';
import EventAvailableRoundedIcon from '@mui/icons-material/EventAvailableRounded';
import BoltRoundedIcon from '@mui/icons-material/BoltRounded';
import { isApiError } from '@api/client';
import { AppShell } from '@components/AppShell';
import { QueryStateView } from '@components/QueryStateView';
import { DAYS_OF_WEEK, type DayOfWeek, type ProviderProfile } from './api';
import { useMyProfile, useSaveAvailability, useSaveEmergencyAvailability } from './hooks';
import { ProfileRequired } from './ProfileRequired';
import {
  availabilityFormDefaults,
  availabilitySchema,
  toAvailabilityPayload,
  type AvailabilityFormValues,
} from './schemas';

const DAY_LABELS: Record<DayOfWeek, string> = {
  MONDAY: 'Monday',
  TUESDAY: 'Tuesday',
  WEDNESDAY: 'Wednesday',
  THURSDAY: 'Thursday',
  FRIDAY: 'Friday',
  SATURDAY: 'Saturday',
  SUNDAY: 'Sunday',
};

/** "9 AM", "12 PM", and "Midnight" for both ends of the day. */
function formatHour(hour: number): string {
  if (hour === 0 || hour === 24) return 'Midnight';
  if (hour === 12) return 'Noon';
  return hour < 12 ? `${hour} AM` : `${hour - 12} PM`;
}

const START_HOURS = Array.from({ length: 24 }, (_, hour) => hour);
const END_HOURS = Array.from({ length: 24 }, (_, index) => index + 1);

/** New ranges default to a working day, or follow on from the day's last range. */
function nextRange(lastEndHour: number | undefined): { startHour: number; endHour: number } {
  if (lastEndHour === undefined || lastEndHour >= 24) return { startHour: 9, endHour: 18 };
  return { startHour: lastEndHour, endHour: Math.min(lastEndHour + 2, 24) };
}

/**
 * Availability (Requirements 4.5, 4.6): the emergency-jobs opt-in, which
 * saves as soon as it is switched, and the weekly hours dispatch may offer
 * jobs in. With no hours set the provider counts as available at any time.
 */
export function AvailabilityScreen() {
  const navigate = useNavigate();
  const profile = useMyProfile();

  return (
    <AppShell title="Availability" onBack={() => navigate('/profile')}>
      <QueryStateView
        isLoading={profile.isLoading}
        isError={profile.isError}
        error={profile.error}
        onRetry={() => void profile.refetch()}
      >
        {profile.data === null ? <ProfileRequired what="your availability" /> : null}
        {profile.data ? (
          <Stack spacing={2}>
            <EmergencyAvailabilityCard profile={profile.data} />
            <WeeklyAvailabilityForm profile={profile.data} onSaved={() => navigate('/profile')} />
          </Stack>
        ) : null}
      </QueryStateView>
    </AppShell>
  );
}

/** The emergency-jobs switch; each change is saved straight away. */
function EmergencyAvailabilityCard({ profile }: { profile: ProviderProfile }) {
  const save = useSaveEmergencyAvailability();
  // While a change is in flight show where it is going, not where it was.
  const checked = save.isPending ? save.variables : profile.emergencyAvailable;

  return (
    <Card>
      <CardContent>
        <Stack direction="row" spacing={1.5} alignItems="flex-start">
          <BoltRoundedIcon color="warning" aria-hidden sx={{ mt: 0.75 }} />
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <FormControlLabel
              sx={{ m: 0, width: '100%', justifyContent: 'space-between' }}
              labelPlacement="start"
              label={
                <Typography variant="subtitle1" fontWeight={700}>
                  Take emergency jobs
                </Typography>
              }
              control={
                <Switch
                  checked={checked}
                  disabled={save.isPending}
                  onChange={(event) => save.mutate(event.target.checked)}
                />
              }
            />
            <Typography variant="body2" color="text.secondary">
              Urgent requests, such as a burst pipe, go only to providers who opt in. They need a
              quick response.
            </Typography>
            {save.isError ? (
              <Alert severity="error" sx={{ mt: 1.5 }}>
                {isApiError(save.error) ? save.error.message : 'Could not update this setting.'}
              </Alert>
            ) : null}
          </Box>
        </Stack>
      </CardContent>
    </Card>
  );
}

interface WeeklyAvailabilityFormProps {
  profile: ProviderProfile;
  onSaved: () => void;
}

export function WeeklyAvailabilityForm({ profile, onSaved }: WeeklyAvailabilityFormProps) {
  const save = useSaveAvailability();
  const {
    control,
    handleSubmit,
    formState: { errors },
  } = useForm<AvailabilityFormValues>({
    resolver: zodResolver(availabilitySchema),
    defaultValues: availabilityFormDefaults(profile),
    mode: 'onSubmit',
  });
  const { fields, append, remove } = useFieldArray({ control, name: 'slots' });
  const mode = useWatch({ control, name: 'mode' });
  const slots = useWatch({ control, name: 'slots' });
  // "Add at least one range" is reported on the array itself.
  const slotsError = errors.slots?.root?.message ?? errors.slots?.message;

  const onSubmit = (values: AvailabilityFormValues) => {
    save.mutate(toAvailabilityPayload(values), { onSuccess: onSaved });
  };

  const addRange = (day: DayOfWeek) => {
    const lastEnd = slots
      .filter((slot) => slot.dayOfWeek === day)
      .reduce<number | undefined>((max, slot) => Math.max(max ?? 0, slot.endHour), undefined);
    append({ dayOfWeek: day, ...nextRange(lastEnd) });
  };

  return (
    <Card>
      <CardContent>
        <Stack
          component="form"
          spacing={1.5}
          noValidate
          onSubmit={(event) => void handleSubmit(onSubmit)(event)}
        >
          <Stack direction="row" spacing={1} alignItems="center">
            <EventAvailableRoundedIcon color="primary" aria-hidden />
            <Typography
              id="weekly-hours-heading"
              variant="subtitle1"
              fontWeight={700}
              component="h2"
            >
              Weekly hours
            </Typography>
          </Stack>

          <Controller
            control={control}
            name="mode"
            render={({ field }) => (
              <RadioGroup
                aria-labelledby="weekly-hours-heading"
                value={field.value}
                onChange={(event) => field.onChange(event.target.value)}
              >
                <FormControlLabel
                  value="anytime"
                  control={<Radio size="small" />}
                  label="Any time — offer me jobs whenever they come in"
                />
                <FormControlLabel
                  value="weekly"
                  control={<Radio size="small" />}
                  label="Only during the hours I set"
                />
              </RadioGroup>
            )}
          />

          {mode === 'weekly' ? (
            <Stack spacing={1.5} divider={<Divider flexItem />}>
              <Typography variant="caption" color="text.secondary">
                Times are India Standard Time. A day with no hours means you are not offered jobs
                that day.
              </Typography>
              {DAYS_OF_WEEK.map((day) => {
                const dayFields = fields
                  .map((field, index) => ({ field, index }))
                  .filter(({ field }) => field.dayOfWeek === day);
                return (
                  <Box key={day}>
                    <Stack direction="row" justifyContent="space-between" alignItems="center">
                      <Typography variant="subtitle2" fontWeight={700} component="h3">
                        {DAY_LABELS[day]}
                      </Typography>
                      <Button
                        size="small"
                        startIcon={<AddRoundedIcon />}
                        onClick={() => addRange(day)}
                        aria-label={`Add hours on ${DAY_LABELS[day]}`}
                      >
                        Add hours
                      </Button>
                    </Stack>
                    {dayFields.length === 0 ? (
                      <Typography variant="body2" color="text.secondary">
                        Not available
                      </Typography>
                    ) : null}
                    <Stack spacing={1} sx={{ mt: dayFields.length > 0 ? 1 : 0 }}>
                      {dayFields.map(({ field, index }) => {
                        const slotErrors = errors.slots?.[index];
                        const message =
                          slotErrors?.startHour?.message ?? slotErrors?.endHour?.message;
                        return (
                          <Box key={field.id}>
                            <Stack direction="row" spacing={1} alignItems="center">
                              <HourSelect
                                control={control}
                                name={`slots.${index}.startHour`}
                                label="From"
                                ariaLabel={`${DAY_LABELS[day]} from`}
                                hours={START_HOURS}
                                error={Boolean(slotErrors?.startHour)}
                              />
                              <HourSelect
                                control={control}
                                name={`slots.${index}.endHour`}
                                label="To"
                                ariaLabel={`${DAY_LABELS[day]} to`}
                                hours={END_HOURS}
                                error={Boolean(slotErrors?.endHour)}
                              />
                              <IconButton
                                aria-label={`Remove ${DAY_LABELS[day]} hours`}
                                onClick={() => remove(index)}
                                size="small"
                              >
                                <DeleteOutlineRoundedIcon />
                              </IconButton>
                            </Stack>
                            {message ? <FormHelperText error>{message}</FormHelperText> : null}
                          </Box>
                        );
                      })}
                    </Stack>
                  </Box>
                );
              })}
            </Stack>
          ) : null}

          {slotsError ? (
            <FormHelperText error role="alert">
              {slotsError}
            </FormHelperText>
          ) : null}

          {save.isError ? (
            <Alert severity="error">
              {isApiError(save.error) ? save.error.message : 'Could not save your hours.'}
            </Alert>
          ) : null}

          <Button type="submit" variant="contained" size="large" disabled={save.isPending}>
            {save.isPending ? 'Saving…' : 'Save hours'}
          </Button>
        </Stack>
      </CardContent>
    </Card>
  );
}

interface HourSelectProps {
  control: Control<AvailabilityFormValues>;
  name: `slots.${number}.startHour` | `slots.${number}.endHour`;
  label: string;
  /** Accessible name, naming the day as well. */
  ariaLabel: string;
  hours: number[];
  error: boolean;
}

/** A native select of whole hours: the platform picker on phones, and easy to test. */
function HourSelect({ control, name, label, ariaLabel, hours, error }: HourSelectProps) {
  return (
    <Controller
      control={control}
      name={name}
      render={({ field }) => (
        <TextField
          select
          label={label}
          size="small"
          fullWidth
          error={error}
          SelectProps={{ native: true }}
          inputProps={{ 'aria-label': ariaLabel }}
          InputLabelProps={{ shrink: true }}
          value={field.value}
          onChange={(event) => field.onChange(Number(event.target.value))}
          onBlur={field.onBlur}
          inputRef={field.ref}
        >
          {hours.map((hour) => (
            <option key={hour} value={hour}>
              {formatHour(hour)}
            </option>
          ))}
        </TextField>
      )}
    />
  );
}
