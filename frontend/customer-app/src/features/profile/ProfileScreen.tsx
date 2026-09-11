import { useNavigate } from 'react-router-dom';
import {
  Avatar,
  Box,
  Button,
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
import PersonRoundedIcon from '@mui/icons-material/PersonRounded';
import HomeWorkRoundedIcon from '@mui/icons-material/HomeWorkRounded';
import ReceiptLongRoundedIcon from '@mui/icons-material/ReceiptLongRounded';
import SupportAgentRoundedIcon from '@mui/icons-material/SupportAgentRounded';
import LogoutRoundedIcon from '@mui/icons-material/LogoutRounded';
import ChevronRightRoundedIcon from '@mui/icons-material/ChevronRightRounded';
import { AppShell } from '@components/AppShell';
import { formatMobileNumber } from '@features/auth/phone';
import { useAuthStore } from '@stores/authStore';

/**
 * Profile screen: the account home base reached from the bottom navigation.
 *
 * It shows the signed-in identity and routes to the areas of the app that are
 * about the customer rather than a single booking. Entries whose screens are
 * not part of the Customer app's screen set (Requirement 28.7) are not shown
 * at all, so nothing here leads to a dead end.
 */
export function ProfileScreen() {
  const navigate = useNavigate();
  const user = useAuthStore((state) => state.user);
  const logout = useAuthStore((state) => state.logout);

  const initials = (user?.displayName ?? '')
    .split(' ')
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase())
    .join('');

  const handleLogout = () => {
    // The store revokes the refresh token server-side before clearing local
    // state. Navigation is not awaited so the button never appears to hang on a
    // slow network; the revoke completes in the background either way.
    void logout();
    navigate('/login', { replace: true });
  };

  return (
    <AppShell>
      <Stack spacing={2}>
        <Card>
          <CardContent>
            <Stack direction="row" spacing={2} alignItems="center">
              <Avatar
                {...(user?.photoUrl ? { src: user.photoUrl } : {})}
                sx={{ width: 60, height: 60, bgcolor: 'primary.main', fontSize: 22 }}
              >
                {initials || <PersonRoundedIcon />}
              </Avatar>
              <Box sx={{ minWidth: 0 }}>
                <Typography variant="h6" component="h1" noWrap>
                  {user?.displayName || 'Your account'}
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  {user?.mobileNumber ? formatMobileNumber(user.mobileNumber) : 'Signed in'}
                </Typography>
                {user?.email ? (
                  <Typography variant="caption" color="text.secondary">
                    {user.email}
                  </Typography>
                ) : null}
              </Box>
            </Stack>
          </CardContent>
        </Card>

        <Card>
          <List disablePadding>
            <ProfileLink
              icon={<ReceiptLongRoundedIcon />}
              label="Your bookings"
              caption="Past and active jobs, with invoices"
              onClick={() => navigate('/history')}
            />
            <Divider component="li" />
            <ProfileLink
              icon={<HomeWorkRoundedIcon />}
              label="Service addresses"
              caption="Saved with each booking you make"
              onClick={() => navigate('/history')}
            />
            <Divider component="li" />
            <ProfileLink
              icon={<SupportAgentRoundedIcon />}
              label="Help & support"
              caption="Raise a complaint or reach the team"
              onClick={() => navigate('/help')}
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
          HomeFix — Verified Help. Anytime. Anywhere.
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
