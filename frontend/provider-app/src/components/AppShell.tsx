import type { ReactNode } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import {
  AppBar,
  Avatar,
  BottomNavigation,
  BottomNavigationAction,
  Box,
  Button,
  Container,
  IconButton,
  Paper,
  Stack,
  Toolbar,
  Tooltip,
  Typography,
} from '@mui/material';
import DashboardRoundedIcon from '@mui/icons-material/DashboardRounded';
import AccountBalanceWalletRoundedIcon from '@mui/icons-material/AccountBalanceWalletRounded';
import VerifiedUserRoundedIcon from '@mui/icons-material/VerifiedUserRounded';
import LogoutRoundedIcon from '@mui/icons-material/LogoutRounded';
import ArrowBackRoundedIcon from '@mui/icons-material/ArrowBackRounded';
import PersonRoundedIcon from '@mui/icons-material/PersonRounded';
import { useAuthStore } from '@stores/authStore';
import { BrandLogo } from './BrandLogo';

/** Height reserved for the fixed bottom navigation, in theme spacing units. */
const BOTTOM_NAV_SPACING = 9;

/** Bottom-nav destinations shown on the mobile-first Provider layout. */
const NAV_ITEMS = [
  { label: 'Dashboard', value: '/dashboard', icon: <DashboardRoundedIcon /> },
  { label: 'Earnings', value: '/earnings', icon: <AccountBalanceWalletRoundedIcon /> },
  { label: 'Verification', value: '/verification', icon: <VerifiedUserRoundedIcon /> },
  { label: 'Profile', value: '/profile', icon: <PersonRoundedIcon /> },
] as const;

interface AppShellProps {
  title: string;
  children: ReactNode;
  /**
   * When provided, a back button is shown in the app bar instead of relying on
   * bottom navigation alone. Used by drill-in screens (job request/details/
   * active/completion) that aren't top-level nav destinations.
   */
  onBack?: () => void;
  /** Show the brand mark instead of the title — used by the Dashboard. */
  branded?: boolean;
  /** Optional control rendered before the logout button. */
  action?: ReactNode;
}

/**
 * Mobile-first app shell: a top app bar with the screen title + logout, a
 * constrained content column, and a fixed bottom navigation. The layout is
 * built for small screens first (single column, thumb-reachable bottom nav)
 * and remains centred/comfortable on larger viewports (Requirement 28.2).
 */
export function AppShell({ title, children, onBack, branded = false, action }: AppShellProps) {
  const navigate = useNavigate();
  const location = useLocation();
  const clearSession = useAuthStore((state) => state.clearSession);
  const user = useAuthStore((state) => state.user);

  // Highlight the nav item whose route prefixes the current path.
  const activeValue =
    NAV_ITEMS.find((item) => location.pathname.startsWith(item.value))?.value ?? false;

  const initials = (user?.displayName ?? '')
    .split(' ')
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase())
    .join('');

  const handleLogout = () => {
    clearSession();
    navigate('/login', { replace: true });
  };

  return (
    <Box
      sx={{
        minHeight: '100dvh',
        display: 'flex',
        flexDirection: 'column',
        // Bottom nav is a phone affordance; on desktop the tabs live in the bar.
        pb: { xs: BOTTOM_NAV_SPACING, md: 0 },
      }}
    >
      <AppBar position="sticky">
        <Toolbar sx={{ gap: 1 }}>
          {onBack ? (
            <IconButton edge="start" onClick={onBack} aria-label="Go back">
              <ArrowBackRoundedIcon />
            </IconButton>
          ) : null}

          {branded && !onBack ? (
            <BrandLogo size={34} showRole />
          ) : (
            <Typography variant="h6" component="h1" noWrap>
              {title}
            </Typography>
          )}

          <Stack direction="row" spacing={0.5} sx={{ display: { xs: 'none', md: 'flex' }, ml: 3 }}>
            {NAV_ITEMS.map((item) => (
              <Button
                key={item.value}
                onClick={() => navigate(item.value)}
                startIcon={item.icon}
                sx={{
                  color: activeValue === item.value ? 'primary.main' : 'text.secondary',
                  fontWeight: activeValue === item.value ? 700 : 500,
                }}
              >
                {item.label}
              </Button>
            ))}
          </Stack>

          <Box sx={{ flexGrow: 1 }} />
          {action}

          <Tooltip title={user?.displayName ?? 'Profile'}>
            <IconButton onClick={() => navigate('/profile')} aria-label="Open profile" size="small">
              <Avatar sx={{ width: 32, height: 32, bgcolor: 'primary.main', fontSize: 13 }}>
                {initials || <PersonRoundedIcon sx={{ fontSize: 18 }} />}
              </Avatar>
            </IconButton>
          </Tooltip>
          <Tooltip title="Log out">
            <IconButton edge="end" onClick={handleLogout} aria-label="Log out" size="small">
              <LogoutRoundedIcon />
            </IconButton>
          </Tooltip>
        </Toolbar>
      </AppBar>

      <Container
        maxWidth="md"
        component="main"
        sx={{ flexGrow: 1, py: { xs: 2.5, md: 4 }, px: { xs: 2, md: 3 } }}
      >
        {children}
      </Container>

      <Paper
        elevation={0}
        square
        sx={{
          position: 'fixed',
          bottom: 0,
          left: 0,
          right: 0,
          zIndex: (t) => t.zIndex.appBar,
          display: { xs: 'block', md: 'none' },
        }}
      >
        <BottomNavigation
          showLabels
          value={activeValue}
          onChange={(_event, value: string) => navigate(value)}
        >
          {NAV_ITEMS.map((item) => (
            <BottomNavigationAction
              key={item.value}
              label={item.label}
              value={item.value}
              icon={item.icon}
            />
          ))}
        </BottomNavigation>
      </Paper>
    </Box>
  );
}
