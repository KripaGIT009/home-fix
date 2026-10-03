import type { ReactNode } from 'react';
import { Link as RouterLink, useLocation, useNavigate } from 'react-router-dom';
import {
  AppBar,
  Avatar,
  BottomNavigation,
  BottomNavigationAction,
  Box,
  Button,
  ButtonBase,
  Container,
  IconButton,
  Link,
  Paper,
  Stack,
  Toolbar,
  Tooltip,
  Typography,
  useMediaQuery,
} from '@mui/material';
import { useTheme } from '@mui/material/styles';
import HomeRoundedIcon from '@mui/icons-material/HomeRounded';
import ReceiptLongRoundedIcon from '@mui/icons-material/ReceiptLongRounded';
import SupportAgentRoundedIcon from '@mui/icons-material/SupportAgentRounded';
import PersonRoundedIcon from '@mui/icons-material/PersonRounded';
import PlaceRoundedIcon from '@mui/icons-material/PlaceRounded';
import ArrowBackRoundedIcon from '@mui/icons-material/ArrowBackRounded';
import { useAuthStore } from '@stores/authStore';
import { env } from '@config/env';
import { brand, layout, radius, visuallyHidden } from '@lib/theme';
import { BrandLogo } from './BrandLogo';
import { ServiceSearchField } from './ServiceSearchField';

/** Primary destinations. Four, so each stays thumb-reachable on the tab bar. */
const NAV_ITEMS = [
  { label: 'Home', value: '/home', icon: <HomeRoundedIcon /> },
  { label: 'Bookings', value: '/history', icon: <ReceiptLongRoundedIcon /> },
  { label: 'Help', value: '/help', icon: <SupportAgentRoundedIcon /> },
  { label: 'Profile', value: '/profile', icon: <PersonRoundedIcon /> },
] as const;

/** The service area shown in the bar until the profile carries one. */
export const DEFAULT_SERVICE_AREA = 'Ara, Bihar';

/**
 * Desktop reaches Home through the logo and Profile through the avatar, so
 * only Bookings and Help need text links beside the search field.
 */
const DESKTOP_LINKS = NAV_ITEMS.filter(
  (item) => item.value === '/history' || item.value === '/help',
);

interface AppShellProps {
  children: ReactNode;
  /**
   * Top-level destinations show the branded bar. Drill-in screens pass a
   * `title` and get a back affordance: in the bar on mobile, above the content
   * on desktop (where the main navigation stays visible).
   */
  title?: string;
  /** Supporting line under the title on desktop drill-in screens. */
  subtitle?: ReactNode;
  /** Back handler for drill-in screens; defaults to browser history. */
  onBack?: () => void;
  /** Optional action rendered at the end of the title row. */
  action?: ReactNode;
  /** Hide the bottom tab bar on immersive screens (tracking, chat). */
  hideBottomNav?: boolean;
  /** Service area shown next to the pin on the branded bar. */
  serviceArea?: string;
  /**
   * `full` uses the whole 1200px content width, `content` (default) keeps
   * forms and lists to a readable column, and `bleed` hands the page the full
   * viewport width so it can lay out its own full-bleed bands.
   */
  width?: 'full' | 'content' | 'bleed';
  /** Render the site footer below the content. */
  footer?: boolean;
  /**
   * White page instead of the tinted canvas. Storefront pages use it: their
   * tiles and rails sit directly on the page, like the reference storefront,
   * while form and list screens keep the canvas their cards stand out on.
   */
  plain?: boolean;
}

/**
 * Responsive shell shared by every signed-in screen.
 *
 * Desktop gets a proper top navigation and a centred 1200px content area;
 * mobile keeps a compact app bar and a fixed bottom tab bar so the app feels
 * native inside the Capacitor shell (Requirement 28.2, 28.7).
 */
export function AppShell({
  children,
  title,
  subtitle,
  onBack,
  action,
  hideBottomNav = false,
  serviceArea = DEFAULT_SERVICE_AREA,
  width = 'content',
  footer = false,
  plain = false,
}: AppShellProps) {
  const theme = useTheme();
  const isDesktop = useMediaQuery(theme.breakpoints.up('md'), { noSsr: true });
  const navigate = useNavigate();
  const location = useLocation();
  const user = useAuthStore((state) => state.user);

  const activeValue =
    NAV_ITEMS.find((item) => location.pathname.startsWith(item.value))?.value ?? false;
  const showTabBar = !hideBottomNav && !isDesktop;
  const goBack = () => (onBack ? onBack() : navigate(-1));

  const initials = (user?.displayName ?? '')
    .split(' ')
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase())
    .join('');

  const avatar = (
    <Tooltip title={user?.displayName ?? 'Your profile'}>
      <IconButton onClick={() => navigate('/profile')} aria-label="Open profile" sx={{ p: 0.5 }}>
        <Avatar
          {...(user?.photoUrl ? { src: user.photoUrl } : {})}
          sx={{
            width: 36,
            height: 36,
            bgcolor: brand.accentSoft,
            color: 'primary.main',
            fontSize: 14,
            border: `1px solid ${brand.accentLine}`,
          }}
        >
          {initials || <PersonRoundedIcon sx={{ fontSize: 20 }} />}
        </Avatar>
      </IconButton>
    </Tooltip>
  );

  const locationChip = (
    <Stack
      direction="row"
      spacing={0.5}
      alignItems="center"
      sx={{
        px: 1.25,
        height: isDesktop ? 44 : 34,
        borderRadius: `${radius.sm}px`,
        border: `1px solid ${isDesktop ? brand.lineStrong : brand.line}`,
        bgcolor: 'background.paper',
        color: 'text.primary',
        minWidth: 0,
        flexShrink: 0,
      }}
    >
      <PlaceRoundedIcon sx={{ fontSize: 18, color: 'primary.main' }} aria-hidden />
      <Typography variant="body2" fontWeight={500} noWrap sx={{ maxWidth: 140 }}>
        <Box component="span" sx={visuallyHidden}>
          Service area:{' '}
        </Box>
        {serviceArea}
      </Typography>
    </Stack>
  );

  const showMobileDrillBar = Boolean(title) && !isDesktop;

  return (
    <Box
      sx={{
        minHeight: '100dvh',
        display: 'flex',
        flexDirection: 'column',
        bgcolor: plain ? 'background.paper' : 'background.default',
        pb: showTabBar ? `calc(${layout.bottomNavHeight}px + env(safe-area-inset-bottom))` : 0,
      }}
    >
      <AppBar position="sticky">
        {showMobileDrillBar ? (
          <Toolbar sx={{ gap: 1, px: { xs: 1, sm: 2 } }}>
            <IconButton onClick={goBack} aria-label="Go back">
              <ArrowBackRoundedIcon />
            </IconButton>
            <Typography variant="h6" component="h1" noWrap sx={{ flexGrow: 1 }}>
              {title}
            </Typography>
            {action}
          </Toolbar>
        ) : (
          <Container maxWidth={false} sx={{ maxWidth: layout.maxWidth, px: layout.gutter }}>
            <Toolbar disableGutters sx={{ gap: { xs: 1, md: 1.5 } }}>
              <ButtonBase
                component={RouterLink}
                to="/home"
                aria-label="HomeFix home"
                sx={{ borderRadius: `${radius.sm}px`, p: 0.5, ml: -0.5, mr: { md: 2.5 } }}
              >
                <BrandLogo size={isDesktop ? 34 : 32} />
              </ButtonBase>

              {isDesktop ? (
                <>
                  {locationChip}
                  <Box sx={{ flexGrow: 1, maxWidth: 440 }}>
                    <ServiceSearchField />
                  </Box>
                  <Box sx={{ flexGrow: 1 }} />
                  <Stack component="nav" aria-label="Main" direction="row" spacing={0.5}>
                    {DESKTOP_LINKS.map((item) => {
                      const active = activeValue === item.value;
                      return (
                        <Button
                          key={item.value}
                          component={RouterLink}
                          to={item.value}
                          aria-current={active ? 'page' : undefined}
                          sx={{
                            px: 1.5,
                            fontSize: '0.875rem',
                            color: active ? 'primary.main' : 'text.primary',
                            fontWeight: active ? 700 : 500,
                            '&:hover': { bgcolor: brand.slateSoft },
                          }}
                        >
                          {item.label}
                        </Button>
                      );
                    })}
                  </Stack>
                  {avatar}
                </>
              ) : (
                <>
                  <Box sx={{ flexGrow: 1 }} />
                  {locationChip}
                  {avatar}
                </>
              )}
            </Toolbar>
          </Container>
        )}
      </AppBar>

      {width === 'bleed' ? (
        <Box component="main" sx={{ flexGrow: 1 }}>
          {children}
        </Box>
      ) : (
        <Container
          component="main"
          maxWidth={false}
          sx={{
            flexGrow: 1,
            maxWidth: width === 'full' ? layout.maxWidth : layout.readingWidth,
            px: layout.gutter,
            py: { xs: 2.5, md: 5 },
          }}
        >
          {title && isDesktop ? (
            <Stack direction="row" spacing={2} alignItems="center" sx={{ mb: 4 }}>
              <IconButton
                onClick={goBack}
                aria-label="Go back"
                sx={{ border: `1px solid ${brand.line}`, bgcolor: 'background.paper' }}
              >
                <ArrowBackRoundedIcon />
              </IconButton>
              <Box sx={{ flexGrow: 1, minWidth: 0 }}>
                <Typography variant="h3" component="h1" noWrap>
                  {title}
                </Typography>
                {subtitle ? (
                  <Typography variant="body2" color="text.secondary" component="div">
                    {subtitle}
                  </Typography>
                ) : null}
              </Box>
              {action}
            </Stack>
          ) : null}
          {children}
        </Container>
      )}

      {footer ? <SiteFooter /> : null}

      {showTabBar ? (
        <Paper
          elevation={0}
          square
          component="nav"
          aria-label="Main"
          sx={{
            position: 'fixed',
            bottom: 0,
            left: 0,
            right: 0,
            zIndex: (t) => t.zIndex.appBar,
            borderTop: `1px solid ${brand.line}`,
            pb: 'env(safe-area-inset-bottom)',
            bgcolor: 'rgba(255,255,255,0.96)',
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
      ) : null}
    </Box>
  );
}

/** Footer links. Only routes that exist: there are no Terms or Privacy pages yet. */
const CUSTOMER_LINKS = [
  { label: 'Bookings', to: '/history' },
  { label: 'Help & support', to: '/help' },
  { label: 'Your profile', to: '/profile' },
] as const;

/** Site footer for top-level pages, on a light grey band. */
function SiteFooter() {
  const heading = (text: string) => (
    <Typography
      component="h2"
      sx={{ fontSize: '0.9375rem', fontWeight: 700, color: 'text.primary', mb: 1.25 }}
    >
      {text}
    </Typography>
  );
  const linkSx = { display: 'block', py: 0.5, width: 'fit-content' } as const;

  return (
    <Box component="footer" sx={{ bgcolor: brand.slateSoft, mt: { xs: 5, md: 8 } }}>
      <Container
        maxWidth={false}
        sx={{ maxWidth: layout.maxWidth, px: layout.gutter, py: { xs: 4, md: 5 } }}
      >
        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: '1fr 1fr', md: '2fr 1fr 1fr' },
            columnGap: { xs: 2, md: 6 },
            rowGap: 4,
          }}
        >
          <Box sx={{ gridColumn: { xs: '1 / -1', md: 'auto' }, maxWidth: 360 }}>
            <BrandLogo size={30} />
            <Typography variant="body2" color="text.secondary" sx={{ mt: 1.5 }}>
              Background-checked professionals for repairs, cleaning and installations, with the
              price shown before you book.
            </Typography>
          </Box>
          <Box component="nav" aria-label="For customers">
            {heading('For customers')}
            {CUSTOMER_LINKS.map((link) => (
              <Link
                key={link.to}
                component={RouterLink}
                to={link.to}
                underline="hover"
                color="text.secondary"
                variant="body2"
                sx={linkSx}
              >
                {link.label}
              </Link>
            ))}
          </Box>
          <Box component="nav" aria-label="For professionals">
            {heading('For professionals')}
            <Link
              href={env.providerAppUrl}
              underline="hover"
              color="text.secondary"
              variant="body2"
              sx={linkSx}
            >
              Join as a professional
            </Link>
          </Box>
        </Box>
        <Box sx={{ mt: { xs: 4, md: 5 }, pt: 2.5, borderTop: `1px solid ${brand.lineStrong}` }}>
          <Typography variant="caption" color="text.secondary">
            © {new Date().getFullYear()} HomeFix. Verified Help. Anytime. Anywhere.
          </Typography>
        </Box>
      </Container>
    </Box>
  );
}
