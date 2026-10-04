import { useNavigate } from 'react-router-dom';
import {
  Avatar,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Divider,
  List,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Stack,
  Typography,
} from '@mui/material';
import PersonRoundedIcon from '@mui/icons-material/PersonRounded';
import VerifiedUserRoundedIcon from '@mui/icons-material/VerifiedUserRounded';
import AccountBalanceWalletRoundedIcon from '@mui/icons-material/AccountBalanceWalletRounded';
import DashboardRoundedIcon from '@mui/icons-material/DashboardRounded';
import LogoutRoundedIcon from '@mui/icons-material/LogoutRounded';
import ChevronRightRoundedIcon from '@mui/icons-material/ChevronRightRounded';
import VerifiedRoundedIcon from '@mui/icons-material/VerifiedRounded';
import { AppShell } from '@components/AppShell';
import { QueryStateView } from '@components/QueryStateView';
import { formatMobileNumber } from '@features/auth/phone';
import { useVerificationState } from '@features/verification/hooks';
import { describeVerificationStatus } from '@features/verification/status';
import { useAuthStore } from '@stores/authStore';
import { BankAccountCard } from './BankAccountCard';
import { EmailPasswordCard } from './EmailPasswordCard';
import { WorkProfileCard } from './WorkProfileCard';

/**
 * Provider profile: identity, the live verification badge, the work profile
 * dispatch matches on (services, service area, availability), the bank account
 * settlements are paid into, the email and password the account signs in
 * with, and the routes into the other top-level areas.
 *
 * Verification status is the fact a provider checks most often — it decides
 * whether they can be dispatched at all (Requirement 5) — so it is shown here
 * rather than only on its own screen.
 */
export function ProfileScreen() {
  const navigate = useNavigate();
  const user = useAuthStore((state) => state.user);
  const logout = useAuthStore((state) => state.logout);
  const verification = useVerificationState();

  const initials = (user?.displayName ?? '')
    .split(' ')
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase())
    .join('');

  const status = verification.data ? describeVerificationStatus(verification.data.status) : null;
  const isApproved = verification.data?.status === 'APPROVED';

  const handleLogout = () => {
    // The store revokes the refresh token server-side before clearing local
    // state. Navigation is not awaited so the button never appears to hang on a
    // slow network; the revoke completes in the background either way.
    void logout();
    navigate('/login', { replace: true });
  };

  return (
    <AppShell title="My profile">
      <Stack spacing={2}>
        <Card>
          <CardContent>
            <Stack direction="row" spacing={2} alignItems="center">
              <Avatar sx={{ width: 60, height: 60, bgcolor: 'primary.main', fontSize: 22 }}>
                {initials || <PersonRoundedIcon />}
              </Avatar>
              <Box sx={{ minWidth: 0 }}>
                <Typography variant="h6" component="h1" noWrap>
                  {user?.displayName || 'Your account'}
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  {user?.mobileNumber ? formatMobileNumber(user.mobileNumber) : 'Signed in'}
                </Typography>
                <QueryStateView
                  isLoading={verification.isLoading}
                  isError={verification.isError}
                  error={verification.error}
                >
                  {status ? (
                    <Chip
                      size="small"
                      sx={{ mt: 0.75 }}
                      color={isApproved ? 'success' : 'default'}
                      {...(isApproved
                        ? { icon: <VerifiedRoundedIcon sx={{ fontSize: 14 }} /> }
                        : {})}
                      label={status.label}
                    />
                  ) : null}
                </QueryStateView>
              </Box>
            </Stack>
          </CardContent>
        </Card>

        <WorkProfileCard />

        <BankAccountCard />

        <EmailPasswordCard />

        <Card>
          <List disablePadding>
            <ProfileLink
              icon={<VerifiedUserRoundedIcon />}
              label="Verification"
              caption="Documents, background check and status"
              onClick={() => navigate('/verification')}
            />
            <Divider component="li" />
            <ProfileLink
              icon={<AccountBalanceWalletRoundedIcon />}
              label="Earnings & settlements"
              caption="Wallet balance, payouts and history"
              onClick={() => navigate('/earnings')}
            />
            <Divider component="li" />
            <ProfileLink
              icon={<DashboardRoundedIcon />}
              label="Active jobs"
              caption="Everything you are working on now"
              onClick={() => navigate('/dashboard')}
            />
          </List>
        </Card>

        <Button
          variant="outlined"
          color="error"
          size="large"
          fullWidth
          startIcon={<LogoutRoundedIcon />}
          onClick={handleLogout}
        >
          Log out
        </Button>

        <Typography variant="caption" color="text.secondary" align="center">
          HomeFix Provider — Earn More. Serve Better.
        </Typography>
      </Stack>
    </AppShell>
  );
}

/** One navigable row in the profile list. */
function ProfileLink({
  icon,
  label,
  caption,
  onClick,
}: {
  icon: React.ReactNode;
  label: string;
  caption: string;
  onClick: () => void;
}) {
  return (
    <ListItemButton onClick={onClick} sx={{ py: 1.5, borderRadius: 0 }}>
      <ListItemIcon sx={{ color: 'primary.main', minWidth: 42 }}>{icon}</ListItemIcon>
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
