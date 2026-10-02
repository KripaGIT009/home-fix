import type { ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Avatar,
  Box,
  Button,
  Card,
  Divider,
  List,
  ListItemButton,
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
import VerifiedUserRoundedIcon from '@mui/icons-material/VerifiedUserRounded';
import { AppShell } from '@components/AppShell';
import { IconTile } from '@components/StateViews';
import { formatMobileNumber } from '@features/auth/phone';
import { useAuthStore } from '@stores/authStore';
import { brand, radius } from '@lib/theme';

/**
 * Profile screen: the account home base reached from the main navigation.
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
      <Stack spacing={{ xs: 2.5, md: 3 }}>
        <Typography variant="h2" component="h1" sx={{ display: { xs: 'none', md: 'block' } }}>
          Your profile
        </Typography>

        <Card
          sx={{
            background: `linear-gradient(135deg, ${brand.accentSoft} 0%, #FFFFFF 70%)`,
            borderColor: brand.accentLine,
          }}
        >
          <Stack direction="row" spacing={2.5} alignItems="center" sx={{ p: { xs: 2.5, md: 3.5 } }}>
            <Avatar
              {...(user?.photoUrl ? { src: user.photoUrl } : {})}
              sx={{ width: 72, height: 72, bgcolor: 'primary.main', fontSize: 26 }}
            >
              {initials || <PersonRoundedIcon sx={{ fontSize: 36 }} />}
            </Avatar>
            <Box sx={{ minWidth: 0 }}>
              <Typography variant="h4" component="h2" noWrap>
                {user?.displayName || 'Your account'}
              </Typography>
              <Typography variant="body1" color="text.secondary">
                {user?.mobileNumber ? formatMobileNumber(user.mobileNumber) : 'Signed in'}
              </Typography>
              {user?.email ? (
                <Typography variant="body2" color="text.secondary" noWrap>
                  {user.email}
                </Typography>
              ) : null}
              <Stack
                direction="row"
                spacing={0.5}
                alignItems="center"
                sx={{
                  display: 'inline-flex',
                  mt: 1,
                  px: 1,
                  py: 0.25,
                  borderRadius: `${radius.pill}px`,
                  bgcolor: brand.greenSoft,
                  color: '#05603A',
                }}
              >
                <VerifiedUserRoundedIcon sx={{ fontSize: 14 }} aria-hidden />
                <Typography variant="caption" fontWeight={700}>
                  Number verified
                </Typography>
              </Stack>
            </Box>
          </Stack>
        </Card>

        <Card>
          <List disablePadding aria-label="Account">
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
              caption="Answers, and a way through to our team"
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
          sx={{ borderColor: '#F7D4D0', bgcolor: 'background.paper' }}
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
  icon: ReactNode;
  label: string;
  caption: string;
  onClick: () => void;
}) {
  return (
    <ListItemButton onClick={onClick} sx={{ py: 2, px: { xs: 2, md: 3 }, borderRadius: 0, gap: 2 }}>
      <IconTile size={40}>{icon}</IconTile>
      <ListItemText
        primary={label}
        secondary={caption}
        primaryTypographyProps={{ variant: 'subtitle1', fontWeight: 700 }}
        secondaryTypographyProps={{ variant: 'body2' }}
      />
      <ChevronRightRoundedIcon sx={{ color: 'text.disabled' }} />
    </ListItemButton>
  );
}
