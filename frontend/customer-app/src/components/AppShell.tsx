import type { ReactNode } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import {
  AppBar,
  Avatar,
  Badge,
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
import HomeRoundedIcon from '@mui/icons-material/HomeRounded';
import ReceiptLongRoundedIcon from '@mui/icons-material/ReceiptLongRounded';
import SupportAgentRoundedIcon from '@mui/icons-material/SupportAgentRounded';
import PersonRoundedIcon from '@mui/icons-material/PersonRounded';
import PlaceRoundedIcon from '@mui/icons-material/PlaceRounded';
import NotificationsNoneRoundedIcon from '@mui/icons-material/NotificationsNoneRounded';
import ArrowBackRoundedIcon from '@mui/icons-material/ArrowBackRounded';
import { useAuthStore } from '@stores/authStore';
import { BrandLogo } from './BrandLogo';

/** Height reserved for the fixed bottom navigation, in theme spacing units. */
const BOTTOM_NAV_SPACING = 9;

/** Bottom-nav destinations. Kept to four so each stays thumb-reachable. */
const NAV_ITEMS = [
  { label: 'Home', value: '/home', icon: <HomeRoundedIcon /> },
  { label: 'Bookings', value: '/history', icon: <ReceiptLongRoundedIcon /> },
  { label: 'Help', value: '/help', icon: <SupportAgentRoundedIcon /> },
  { label: 'Profile', value: '/profile', icon: <PersonRoundedIcon /> },
] as const;

interface AppShellProps {
  children: ReactNode;
  /**
   * Top-level destinations show the branded bar (logo, service area, profile).
   * Drill-in screens pass a `title` instead and get a back button.
   */
  title?: string;
  /** Back handler for drill-in screens; defaults to browser history. */
  onBack?: () => void;
  /** Optional action rendered at the end of a drill-in app bar. */
  action?: ReactNode;
  /** Hide the bottom navigation on immersive screens (tracking, chat). */
  hideBottomNav?: boolean;
  /** Service area shown next to the pin on the branded bar. */
  serviceArea?: string;
  /**
   * `full` lets the storefront use the whole width on desktop; `content`
   * (default) keeps forms, lists and detail pages to a readable column so a
   * booking form never stretches across a 1900px monitor.
   */
  width?: 'full' | 'content';
}

/**
 * Mobile-first shell shared by every Customer screen: a sticky app bar, a
 * single centred content column, and a fixed bottom navigation.
 *
 * Two bar variants keep the hierarchy obvious — branded (top-level tabs) and
 * titled-with-back (drill-in) — so a customer always knows whether they are at
 * a home base or partway through a booking flow (Requirement 28.2, 28.7).
 */
export function AppShell({
  children,
  title,
  onBack,
  action,
  hideBottomNav = false,
  serviceArea = 'Ara, Bihar',
  width = 'content',
}: AppShellProps) {
  const navigate = useNavigate();
  const location = useLocation();
  const user = useAuthStore((state) => state.user);

  const activeValue =
    NAV_ITEMS.find((item) => location.pathname.startsWith(item.value))?.value ?? false;

  const initials = (user?.displayName ?? '')
    .split(' ')
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase())
    .join('');

  return (
    <Box
      sx={{
        minHeight: '100dvh',
        display: 'flex',
        flexDirection: 'column',
        pb: { xs: hideBottomNav ? 0 : BOTTOM_NAV_SPACING, md: 0 },
      }}
    >
      <AppBar position="sticky">
        <Toolbar sx={{ gap: 1 }}>
          {title ? (
            <>
              <IconButton
                edge="start"
                onClick={() => (onBack ? onBack() : navigate(-1))}
                aria-label="Go back"
              >
                <ArrowBackRoundedIcon />
              </IconButton>
              <Typography variant="h6" component="h1" noWrap sx={{ flexGrow: 1 }}>
                {title}
              </Typography>
              {action}
            </>
          ) : (
            <>
              <BrandLogo size={34} />
              <Stack
                direction="row"
                spacing={0.5}
                sx={{ display: { xs: 'none', md: 'flex' }, ml: 3 }}
              >
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
              <Stack
                direction="row"
                spacing={0.25}
                alignItems="center"
                sx={{ color: 'text.secondary', mr: 0.5 }}
              >
                <PlaceRoundedIcon sx={{ fontSize: 18, color: 'primary.main' }} aria-hidden />
                <Typography variant="body2" fontWeight={600} noWrap sx={{ maxWidth: 120 }}>
                  {serviceArea}
                </Typography>
              </Stack>
              <Tooltip title="Notifications">
                <IconButton aria-label="Notifications" size="small">
                  <Badge color="error" variant="dot">
                    <NotificationsNoneRoundedIcon />
                  </Badge>
                </IconButton>
              </Tooltip>
              <Tooltip title={user?.displayName ?? 'Profile'}>
                <IconButton
                  onClick={() => navigate('/profile')}
                  aria-label="Open profile"
                  size="small"
                >
                  <Avatar
                    {...(user?.photoUrl ? { src: user.photoUrl } : {})}
                    sx={{ width: 32, height: 32, bgcolor: 'primary.main', fontSize: 13 }}
                  >
                    {initials || <PersonRoundedIcon sx={{ fontSize: 18 }} />}
                  </Avatar>
                </IconButton>
              </Tooltip>
            </>
          )}
        </Toolbar>
      </AppBar>

      <Container
        maxWidth={width === 'full' ? 'lg' : 'md'}
        component="main"
        sx={{ flexGrow: 1, py: { xs: 2.5, md: 4 }, px: { xs: 2, md: 3 } }}
      >
        {children}
      </Container>

      {hideBottomNav ? null : (
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
      )}
    </Box>
  );
}
