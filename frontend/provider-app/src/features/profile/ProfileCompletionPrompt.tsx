import { Link as RouterLink } from 'react-router-dom';
import { Alert, AlertTitle, Box, Button } from '@mui/material';
import { useVerificationState } from '@features/verification/hooks';
import { assessProfile, PROFILE_STEP_LABELS, PROFILE_STEP_ROUTES } from './completeness';
import { useMyProfile } from './hooks';

/**
 * Dashboard prompt for a provider dispatch cannot match yet: an incomplete
 * work profile first, then a profile under review, then verification not yet
 * approved. Renders nothing while loading or when either read fails — the
 * prompt is a nudge, and the dashboard must not depend on it.
 */
export function ProfileCompletionPrompt() {
  const profile = useMyProfile();
  const verification = useVerificationState();

  if (profile.data === undefined) return null;
  const completeness = assessProfile(profile.data);

  if (!completeness.complete) {
    const [firstStep] = completeness.missing;
    return (
      <Alert
        severity="warning"
        action={
          firstStep ? (
            <Button
              component={RouterLink}
              to={PROFILE_STEP_ROUTES[firstStep]}
              color="inherit"
              size="small"
            >
              Complete
            </Button>
          ) : null
        }
      >
        <AlertTitle>Complete your profile to receive jobs</AlertTitle>
        HomeFix can only offer you jobs once it knows what you do and where you work.
        <Box component="ul" sx={{ m: 0, mt: 0.5, pl: 2.5 }}>
          {completeness.missing.map((step) => (
            <li key={step}>{PROFILE_STEP_LABELS[step]}</li>
          ))}
        </Box>
      </Alert>
    );
  }

  if (completeness.underReview) {
    return (
      <Alert severity="info">
        <AlertTitle>Your profile is under review</AlertTitle>
        You will not be offered new jobs until HomeFix finishes the review.
      </Alert>
    );
  }

  const status = verification.data?.status;
  if (status && status !== 'APPROVED') {
    return (
      <Alert
        severity="info"
        action={
          <Button component={RouterLink} to="/verification" color="inherit" size="small">
            View
          </Button>
        }
      >
        <AlertTitle>Verification not complete yet</AlertTitle>
        Your profile is ready. You will start receiving jobs once HomeFix approves your
        verification.
      </Alert>
    );
  }

  return null;
}
