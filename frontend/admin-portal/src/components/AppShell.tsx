import { useMemo, useState, type ReactNode } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import {
  AppBar,
  Avatar,
  Box,
  Chip,
  Divider,
  Drawer,
  IconButton,
  List,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  ListSubheader,
  Menu,
  MenuItem,
  Stack,
  Toolbar,
  Tooltip,
  Typography,
} from '@mui/material';
import MenuRoundedIcon from '@mui/icons-material/MenuRounded';
import LogoutRoundedIcon from '@mui/icons-material/LogoutRounded';
import ExpandMoreRoundedIcon from '@mui/icons-material/ExpandMoreRounded';
import { BrandLogo } from './BrandLogo';
import { useAuthStore } from '@stores/authStore';
import { visibleNavItems } from '@config/navFilter';
import { NAV_SECTIONS } from '@config/navSections';
import { brand } from '@lib/theme';

/** Fixed width of the desktop sidebar drawer. */
const DRAWER_WIDTH = 268;

interface AppShellProps {
  title: string;
  children: ReactNode;
}

/** Longest-match wins, so /providers does not light up for /provider-earnings. */
function isActive(pathname: string, path: string): boolean {
  return pathname === path || pathname.startsWith(`${path}/`);
}

/**
 * Desktop-first app shell (Requirement 28.3): a permanent dark navigation rail
 * carrying the operational modules, a top bar with the current module title and
 * the signed-in operator, and a scrollable content region. On small screens the
 * rail collapses into a temporary drawer toggled from the top bar.
 *
 * The rail is dark on purpose. With sixteen modules listed, a white sidebar and
 * a white content area blur into one surface and the eye has to hunt for the
 * boundary; a dark rail fixes the left edge and frees the accent colour to mean
 * "selected" rather than merely decorating chrome.
 *
 * Modules are grouped into sections rather than listed flat, because sixteen
 * undifferentiated links is a menu you read every time instead of a map you
 * learn. The grouping is presentational only — access is still decided per item
 * by roles, so System Configuration appears for SUPER_ADMIN alone
 * (Requirement 19.6) and an empty section renders nothing at all.
 */
export function AppShell({ title, children }: AppShellProps) {
  const navigate = useNavigate();
  const location = useLocation();
  const user = useAuthStore((state) => state.user);
  const logout = useAuthStore((state) => state.logout);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [menuAnchor, setMenuAnchor] = useState<HTMLElement | null>(null);

  const items = useMemo(() => visibleNavItems(user?.roles ?? []), [user?.roles]);

  /** The visible items regrouped into their sections; empty sections drop out. */
  const sections = useMemo(
    () =>
      NAV_SECTIONS.map((section) => ({
        title: section.title,
        items: items.filter((item) => section.paths.includes(item.path)),
      })).filter((section) => section.items.length > 0),
    [items],
  );

  const roleLabel = useMemo(() => {
    const roles = user?.roles ?? [];
    if (roles.includes('SUPER_ADMIN')) return 'Super Admin';
    if (roles.includes('ADMIN')) return 'Admin';
    if (roles.includes('FINANCE_ADMIN')) return 'Finance Admin';
    if (roles.includes('DISPATCHER')) return 'Dispatcher';
    if (roles.includes('SUPPORT_AGENT')) return 'Support Agent';
    return 'Staff';
  }, [user?.roles]);

  const operatorName = user?.displayName ?? 'Administrator';
  const initials = operatorName.slice(0, 2).toUpperCase();

  const handleLogout = () => {
    setMenuAnchor(null);
    // The store revokes the refresh token server-side before clearing local
    // state. Navigation is not awaited so the button never appears to hang on a
    // slow network; the revoke completes in the background either way.
    void logout();
    navigate('/login', { replace: true });
  };

  const handleNavigate = (path: string) => {
    navigate(path);
    setMobileOpen(false);
  };

  const drawerContent = (
    <Box
      sx={{
        display: 'flex',
        flexDirection: 'column',
        height: '100%',
        bgcolor: brand.navBg,
        color: brand.navTextActive,
      }}
    >
      <Toolbar sx={{ gap: 1.25, borderBottom: `1px solid ${brand.navLine}` }}>
        <BrandLogo size={30} inverted />
        <Box
          sx={{
            px: 0.85,
            py: 0.25,
            borderRadius: 1,
            bgcolor: 'rgba(255,255,255,0.10)',
            border: `1px solid ${brand.navLine}`,
          }}
        >
          <Typography
            variant="overline"
            sx={{ color: brand.navText, letterSpacing: '0.12em', lineHeight: 1.6, fontSize: 10 }}
          >
            Ops
          </Typography>
        </Box>
      </Toolbar>

      <Box sx={{ flexGrow: 1, overflowY: 'auto', py: 1.5 }}>
        {sections.map((section) => (
          <List
            key={section.title}
            component="nav"
            aria-label={section.title}
            disablePadding
            sx={{ mb: 1 }}
            subheader={
              <ListSubheader
                disableSticky
                sx={{
                  bgcolor: 'transparent',
                  color: 'rgba(148, 163, 184, 0.7)',
                  fontSize: 10,
                  fontWeight: 700,
                  letterSpacing: '0.11em',
                  textTransform: 'uppercase',
                  lineHeight: 2.4,
                  px: 3,
                }}
              >
                {section.title}
              </ListSubheader>
            }
          >
            {section.items.map((item) => {
              const selected = isActive(location.pathname, item.path);
              return (
                <ListItemButton
                  key={item.path}
                  selected={selected}
                  onClick={() => handleNavigate(item.path)}
                  sx={{
                    mx: 1.25,
                    px: 1.5,
                    py: 0.85,
                    borderRadius: 2,
                    mb: 0.25,
                    color: brand.navText,
                    '&:hover': {
                      bgcolor: brand.navBgRaised,
                      color: brand.navTextActive,
                      '& .MuiListItemIcon-root': { color: brand.navTextActive },
                    },
                    '&.Mui-selected': {
                      bgcolor: 'rgba(37, 99, 235, 0.22)',
                      color: brand.navTextActive,
                      '& .MuiListItemIcon-root': { color: '#93C5FD' },
                      '&:hover': { bgcolor: 'rgba(37, 99, 235, 0.3)' },
                    },
                  }}
                >
                  <ListItemIcon sx={{ minWidth: 34, color: 'inherit', '& svg': { fontSize: 20 } }}>
                    {item.icon}
                  </ListItemIcon>
                  <ListItemText
                    primary={item.label}
                    primaryTypographyProps={{
                      variant: 'body2',
                      fontWeight: selected ? 600 : 500,
                      fontSize: '0.8125rem',
                    }}
                  />
                </ListItemButton>
              );
            })}
          </List>
        ))}
      </Box>

      <Box sx={{ p: 2, borderTop: `1px solid ${brand.navLine}` }}>
        <Typography variant="caption" sx={{ color: 'rgba(148, 163, 184, 0.65)' }}>
          Signed in as {roleLabel}
        </Typography>
      </Box>
    </Box>
  );

  return (
    <Box sx={{ display: 'flex', minHeight: '100vh' }}>
      <AppBar
        position="fixed"
        color="inherit"
        elevation={0}
        sx={{
          zIndex: (t) => t.zIndex.drawer + 1,
          borderBottom: (t) => `1px solid ${t.palette.divider}`,
          // The rail owns the far left; the bar starts where the content does,
          // so the brand mark is never stated twice on the same row.
          width: { md: `calc(100% - ${DRAWER_WIDTH}px)` },
          ml: { md: `${DRAWER_WIDTH}px` },
          backdropFilter: 'saturate(180%) blur(8px)',
          backgroundColor: 'rgba(255, 255, 255, 0.86)',
        }}
      >
        <Toolbar>
          <IconButton
            edge="start"
            aria-label="Toggle navigation"
            onClick={() => setMobileOpen((open) => !open)}
            sx={{ mr: 1, display: { md: 'none' } }}
          >
            <MenuRoundedIcon />
          </IconButton>

          <Box sx={{ flexGrow: 1, minWidth: 0 }}>
            <Typography
              variant="h6"
              component="h1"
              noWrap
              sx={{ fontWeight: 700, lineHeight: 1.2 }}
            >
              {title}
            </Typography>
          </Box>

          <Stack direction="row" spacing={1.25} alignItems="center">
            <Chip
              label={roleLabel}
              size="small"
              variant="outlined"
              sx={{
                display: { xs: 'none', sm: 'inline-flex' },
                color: brand.accent,
                borderColor: 'rgba(37, 99, 235, 0.28)',
                bgcolor: brand.accentSoft,
              }}
            />

            <Tooltip title="Account">
              <Box
                component="button"
                type="button"
                aria-label="Account menu"
                aria-haspopup="menu"
                onClick={(event) => setMenuAnchor(event.currentTarget)}
                sx={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 1,
                  px: 0.75,
                  py: 0.5,
                  border: `1px solid ${brand.line}`,
                  borderRadius: 2,
                  bgcolor: 'transparent',
                  cursor: 'pointer',
                  font: 'inherit',
                  '&:hover': { bgcolor: brand.canvas },
                }}
              >
                <Avatar
                  {...(user?.photoUrl ? { src: user.photoUrl } : {})}
                  alt={operatorName}
                  sx={{ width: 30, height: 30, fontSize: '0.75rem', bgcolor: brand.accent }}
                >
                  {initials}
                </Avatar>
                <Stack alignItems="flex-start" sx={{ display: { xs: 'none', md: 'flex' } }}>
                  <Typography variant="body2" fontWeight={600} lineHeight={1.15}>
                    {operatorName}
                  </Typography>
                  {user?.email ? (
                    <Typography variant="caption" color="text.secondary" lineHeight={1.15}>
                      {user.email}
                    </Typography>
                  ) : null}
                </Stack>
                <ExpandMoreRoundedIcon fontSize="small" sx={{ color: 'text.secondary' }} />
              </Box>
            </Tooltip>

            <Menu
              anchorEl={menuAnchor}
              open={Boolean(menuAnchor)}
              onClose={() => setMenuAnchor(null)}
              anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
              transformOrigin={{ vertical: 'top', horizontal: 'right' }}
            >
              <Box sx={{ px: 1.5, py: 1 }}>
                <Typography variant="body2" fontWeight={600}>
                  {operatorName}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  {user?.email ?? user?.mobileNumber ?? roleLabel}
                </Typography>
              </Box>
              <Divider sx={{ my: 0.5 }} />
              <MenuItem onClick={handleLogout}>
                <LogoutRoundedIcon fontSize="small" />
                Log out
              </MenuItem>
            </Menu>
          </Stack>
        </Toolbar>
      </AppBar>

      <Box
        component="nav"
        sx={{ width: { md: DRAWER_WIDTH }, flexShrink: { md: 0 } }}
        aria-label="Sidebar navigation"
      >
        {/* Temporary drawer for small screens. */}
        <Drawer
          variant="temporary"
          open={mobileOpen}
          onClose={() => setMobileOpen(false)}
          ModalProps={{ keepMounted: true }}
          sx={{
            display: { xs: 'block', md: 'none' },
            '& .MuiDrawer-paper': {
              boxSizing: 'border-box',
              width: DRAWER_WIDTH,
              border: 'none',
            },
          }}
        >
          {drawerContent}
        </Drawer>
        {/* Permanent drawer for desktop. */}
        <Drawer
          variant="permanent"
          open
          sx={{
            display: { xs: 'none', md: 'block' },
            '& .MuiDrawer-paper': {
              boxSizing: 'border-box',
              width: DRAWER_WIDTH,
              border: 'none',
            },
          }}
        >
          {drawerContent}
        </Drawer>
      </Box>

      <Box
        component="main"
        sx={{
          flexGrow: 1,
          width: { md: `calc(100% - ${DRAWER_WIDTH}px)` },
          minWidth: 0,
          bgcolor: 'background.default',
        }}
      >
        <Toolbar />
        <Box sx={{ p: { xs: 2, md: 3 }, maxWidth: 1480, mx: 'auto' }}>{children}</Box>
      </Box>
    </Box>
  );
}
