import { useState } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import { Alert, AlertTitle, Button, Stack, Typography } from '@mui/material';
import LoginRoundedIcon from '@mui/icons-material/LoginRounded';
import LogoutRoundedIcon from '@mui/icons-material/LogoutRounded';
import { QueryStateView } from '@components/QueryStateView';
import { StandalonePage } from '@components/StandalonePage';
import { isStaff } from '@config/roles';
import { formatDateTime } from '@lib/format';
import { useAuthStore } from '@stores/authStore';
import { AgencyApplicationForm } from './AgencyApplicationForm';
import { AgencySteps } from './AgencySteps';
import { useMyApplication } from './hooks';

/**
 * The application status page (email-auth Requirement 5.5): where a signed-in
 * account with no staff role lands instead of an access-denied screen.
 *
 * - no application yet: the agency form (step 2 of "Register your agency");
 * - PENDING_APPROVAL: waiting for HomeFix;
 * - approved (ACTIVE, or SUSPENDED later): the session predates the
 *   TENANT_ADMIN grant, so the person must sign in again to open the portal;
 * - REJECTED: the reason, and a way to apply again.
 *
 * Staff and agency admins have nothing to do here and are sent to their home.
 */
export function AgencyApplicationScreen() {
  const navigate = useNavigate();
  const roles = useAuthStore((state) => state.user?.roles);
  const logout = useAuthStore((state) => state.logout);
  const applicationQuery = useMyApplication();
  const [reapplying, setReapplying] = useState(false);

  if (isStaff(roles ?? [])) {
    return <Navigate to="/" replace />;
  }

  const signOut = () => {
    // Same as the shell's sign-out: revoke in the background, leave at once.
    void logout();
    navigate('/login', { replace: true });
  };

  const application = applicationQuery.data;
  const showForm = application === null || (application?.status === 'REJECTED' && reapplying);
  const approved = application?.status === 'ACTIVE' || application?.status === 'SUSPENDED';

  const title = showForm
    ? 'Tell us about your agency'
    : approved
      ? 'Your agency is approved'
      : application?.status === 'REJECTED'
        ? 'Your application was not approved'
        : 'Your application is under review';

  return (
    <StandalonePage
      title={title}
      description={
        showForm
          ? 'HomeFix reviews every agency before it receives requests. We will email you when your application is decided.'
          : undefined
      }
      width={showForm ? 'md' : 'sm'}
      action={
        <Button size="small" startIcon={<LogoutRoundedIcon />} onClick={signOut}>
          Sign out
        </Button>
      }
    >
      <QueryStateView
        isLoading={applicationQuery.isLoading}
        isError={applicationQuery.isError}
        error={applicationQuery.error}
        onRetry={() => void applicationQuery.refetch()}
      >
        {showForm ? (
          <>
            <AgencySteps active={1} />
            <AgencyApplicationForm
              {...(application ? { initialName: application.name } : {})}
              {...(application ? { onCancel: () => setReapplying(false) } : {})}
            />
          </>
        ) : application ? (
          <Stack spacing={2.5}>
            <AgencySteps active={2} decided={application.status !== 'PENDING_APPROVAL'} />

            {application.status === 'PENDING_APPROVAL' ? (
              <Alert severity="info">
                <AlertTitle>{application.name}</AlertTitle>
                HomeFix is reviewing your application, submitted{' '}
                {formatDateTime(application.createdAt)}. We will email you when it is decided; you
                can also come back here at any time.
              </Alert>
            ) : null}

            {approved ? (
              <>
                <Alert severity="success">
                  <AlertTitle>{application.name}</AlertTitle>
                  Your agency is on HomeFix and you are its administrator. Sign in again to open
                  your agency portal.
                </Alert>
                <Button
                  variant="contained"
                  size="large"
                  endIcon={<LoginRoundedIcon />}
                  onClick={signOut}
                >
                  Sign in again
                </Button>
              </>
            ) : null}

            {application.status === 'REJECTED' ? (
              <>
                <Alert severity="error">
                  <AlertTitle>{application.name}</AlertTitle>
                  {application.rejectionReason ? (
                    <>
                      <Typography variant="body2" component="span" fontWeight={600}>
                        Reason:
                      </Typography>{' '}
                      {application.rejectionReason}
                    </>
                  ) : (
                    'HomeFix did not give a reason.'
                  )}
                </Alert>
                <Typography variant="body2" color="text.secondary">
                  You can correct your details and apply again.
                </Typography>
                <Button variant="contained" size="large" onClick={() => setReapplying(true)}>
                  Apply again
                </Button>
              </>
            ) : null}
          </Stack>
        ) : null}
      </QueryStateView>
    </StandalonePage>
  );
}
