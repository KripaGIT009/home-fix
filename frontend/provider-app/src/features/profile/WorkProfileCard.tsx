import type { ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Alert,
  Card,
  CardContent,
  Divider,
  List,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Stack,
  Typography,
} from '@mui/material';
import CheckCircleRoundedIcon from '@mui/icons-material/CheckCircleRounded';
import ErrorOutlineRoundedIcon from '@mui/icons-material/ErrorOutlineRounded';
import RadioButtonUncheckedRoundedIcon from '@mui/icons-material/RadioButtonUncheckedRounded';
import ChevronRightRoundedIcon from '@mui/icons-material/ChevronRightRounded';
import WorkRoundedIcon from '@mui/icons-material/WorkRounded';
import { QueryStateView } from '@components/QueryStateView';
import type { ProviderProfile } from './api';
import {
  assessProfile,
  AVAILABILITY_ROUTE,
  hasBaseLocation,
  PROFILE_STEP_ROUTES,
} from './completeness';
import { useMyProfile } from './hooks';

type RowState = 'done' | 'needed' | 'optional';

/**
 * The work profile dispatch matches on, as three rows that each open their
 * editor: services & skills, service area, and availability. Rows dispatch
 * cannot do without are marked until they are filled in.
 */
export function WorkProfileCard() {
  const navigate = useNavigate();
  const profile = useMyProfile();

  return (
    <Card>
      <CardContent sx={{ pb: 0 }}>
        <Stack direction="row" spacing={1} alignItems="center">
          <WorkRoundedIcon color="primary" aria-hidden />
          <Typography variant="subtitle1" fontWeight={700} component="h2">
            Work profile
          </Typography>
        </Stack>
      </CardContent>
      <QueryStateView
        isLoading={profile.isLoading}
        isError={profile.isError}
        error={profile.error}
        onRetry={() => void profile.refetch()}
      >
        {profile.data !== undefined ? (
          <WorkProfileRows profile={profile.data} onOpen={(route) => navigate(route)} />
        ) : null}
      </QueryStateView>
    </Card>
  );
}

function WorkProfileRows({
  profile,
  onOpen,
}: {
  profile: ProviderProfile | null;
  onOpen: (route: string) => void;
}) {
  const completeness = assessProfile(profile);

  return (
    <>
      {!completeness.complete ? (
        <CardContent sx={{ pt: 1.5, pb: 1 }}>
          <Alert severity="warning">Finish the marked items to start receiving jobs.</Alert>
        </CardContent>
      ) : null}
      {completeness.underReview ? (
        <CardContent sx={{ pt: 1.5, pb: 1 }}>
          <Alert severity="info">
            Your profile is being reviewed by HomeFix. You will not be offered new jobs until the
            review is finished.
          </Alert>
        </CardContent>
      ) : null}
      <List disablePadding>
        <WorkProfileRow
          label="Services & skills"
          caption={servicesCaption(profile)}
          state={completeness.missing.includes('services') ? 'needed' : 'done'}
          onClick={() => onOpen(PROFILE_STEP_ROUTES.services)}
        />
        <Divider component="li" />
        <WorkProfileRow
          label="Service area"
          caption={
            profile && hasBaseLocation(profile)
              ? `Within ${profile.serviceRadiusKm} km of your base`
              : 'Set your base location and travel radius'
          }
          state={completeness.missing.includes('serviceArea') ? 'needed' : 'done'}
          onClick={() => onOpen(PROFILE_STEP_ROUTES.serviceArea)}
        />
        <Divider component="li" />
        <WorkProfileRow
          label="Availability"
          caption={availabilityCaption(profile)}
          state={profile && profile.availability.length > 0 ? 'done' : 'optional'}
          onClick={() => onOpen(AVAILABILITY_ROUTE)}
        />
      </List>
    </>
  );
}

function servicesCaption(profile: ProviderProfile | null): string {
  if (!profile || profile.skillTags.length === 0) return 'Pick the services you offer';
  const services = profile.categories.reduce(
    (total, category) => total + category.subcategoryIds.length,
    0,
  );
  const skills = profile.skillTags.length;
  const parts = [
    services > 0 ? `${services} service${services === 1 ? '' : 's'}` : null,
    `${skills} skill${skills === 1 ? '' : 's'}`,
    `${profile.yearsExperience} yr${profile.yearsExperience === 1 ? '' : 's'} experience`,
  ];
  return parts.filter(Boolean).join(' · ');
}

function availabilityCaption(profile: ProviderProfile | null): string {
  if (!profile) return 'Weekly hours and emergency jobs';
  const days = new Set(profile.availability.map((slot) => slot.dayOfWeek)).size;
  const hours = days === 0 ? 'Any time' : `${days} day${days === 1 ? '' : 's'} a week`;
  return profile.emergencyAvailable ? `${hours} · Emergency jobs on` : hours;
}

const STATE_ICONS: Record<RowState, ReactNode> = {
  done: <CheckCircleRoundedIcon color="success" titleAccess="Done" />,
  needed: <ErrorOutlineRoundedIcon color="warning" titleAccess="Needed to receive jobs" />,
  optional: <RadioButtonUncheckedRoundedIcon color="disabled" titleAccess="Optional" />,
};

function WorkProfileRow({
  label,
  caption,
  state,
  onClick,
}: {
  label: string;
  caption: string;
  state: RowState;
  onClick: () => void;
}) {
  return (
    <ListItemButton onClick={onClick} sx={{ py: 1.5, borderRadius: 0 }}>
      <ListItemIcon sx={{ minWidth: 42 }}>{STATE_ICONS[state]}</ListItemIcon>
      <ListItemText
        primary={label}
        secondary={caption}
        primaryTypographyProps={{ variant: 'subtitle1', fontWeight: 600 }}
        secondaryTypographyProps={{ variant: 'caption' }}
      />
      <ChevronRightRoundedIcon sx={{ color: 'text.secondary' }} />
    </ListItemButton>
  );
}
