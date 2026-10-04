import { Link as RouterLink } from 'react-router-dom';
import { Alert, Button } from '@mui/material';
import { PROFILE_STEP_ROUTES } from './completeness';

/**
 * Shown by the screens that edit an existing profile (service area,
 * availability) when there is none yet: the Provider Service answers those
 * writes with 404 until the services form has created the profile.
 */
export function ProfileRequired({ what }: { what: string }) {
  return (
    <Alert
      severity="info"
      action={
        <Button
          component={RouterLink}
          to={PROFILE_STEP_ROUTES.services}
          color="inherit"
          size="small"
        >
          Add services
        </Button>
      }
    >
      Add the services you offer first. You can set {what} once that is saved.
    </Alert>
  );
}
