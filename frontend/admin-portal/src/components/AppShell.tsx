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
  Stack,
  Toolbar,
  Tooltip,
  Typography,
} from '@mui/material';
import MenuRoundedIcon from '@mui/icons-material/MenuRounded';
import LogoutRoundedIcon from '@mui/icons-material/LogoutRounded';
import { BrandLogo } from './BrandLogo';
import { useAuthStore } from '@stores/authStore';
import { visibleNavItems } from '@config/navFilter';

/** Fixed width of the desktop sidebar drawer. */
const DRAWER_WIDTH = 264;

interface AppShellProps {
  title: string;
  children: ReactNode;
}

/**
 * Desktop-first app shell (Requirement 28.3): a permanent left sidebar with the
 * operational modules, a top app bar showing the current module title and the
 * signed-in admin, and a scrollable content region. On small screens the
 * sidebar collapses into a temporary drawer toggled from the app bar, but the
 * layout is optimised for large administrative screens.
 *
 * The sidebar is RBAC-aware: System Configuration only appears for SUPER_ADMIN
 * (Requirement 19.6). Hiding it complements the route guard in RequireAuth so
 * ADMIN users neither see nor can navigate to the module.
 */
export function AppShell({ title, children }: AppShellProps) {
  const navigate = useNavigate();
  const location = useLocation();
  const user = useAuthStore((state) => state.user);
  const logout = useAuthStore((state) => state.logout);
  const [mobileOpen, setMobileOpen] = useState(false);

  const items = useMemo(() => visibleNavItems(user?.roles ?? []), [user?.roles]);

  const roleLabel = useMemo(() => {
    const roles = user?.roles ?? [];
    if (roles.includes('SUPER_ADMIN')) return 'Super Admin';
    if (roles.includes('ADMIN')) return 'Admin';
    if (roles.includes('FINANCE_ADMIN')) return 'Finance Admin';
    if (roles.includes('SUPPORT_AGENT')) return 'Support Agent';
    return 'Staff';
  }, [user?.roles]);

  const handleLogout = () => {
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
    <Box sx={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <Toolbar sx={{ gap: 1 }}>
        <BrandLogo size={32} />
        <Typography
          variant="overline"
          sx={{ color: 'text.secondary', letterSpacing: '0.14em', lineHeight: 1 }}
        >
          Admin
        </Typography>
      </Toolbar>
      <Divider />
      <List component="nav" aria-label="Operational modules" sx={{ flexGrow: 1, py: 1 }}>
        {items.map((item) => {
          const selected =
            location.pathname === item.path || location.pathname.startsWith(`${item.path}/`);
          return (
            <ListItemButton
              key={item.path}
              selected={selected}
              onClick={() => handleNavigate(item.path)}
              sx={{
                mx: 1,
                borderRadius: 2,
                mb: 0.25,
                position: 'relative',
                '&.Mui-selected': {
                  color: 'primary.main',
                  '& .MuiListItemIcon-root': { color: 'primary.main' },
                  '&::before': {
                    content: '""',
                    position: 'absolute',
                    left: 0,
                    top: 8,
                    bottom: 8,
                    width: 3,
                    borderRadius: 3,
                    backgroundColor: 'primary.main',
                  },
                },
              }}
            >
              <ListItemIcon sx={{ minWidth: 40, color: 'text.secondary' }}>
                {item.icon}
              </ListItemIcon>
              <ListItemText
                primary={item.label}
                primaryTypographyProps={{
                  variant: 'body2',
                  fontWeight: selected ? 700 : 500,
                }}
              />
            </ListItemButton>
          );
        })}
      </List>
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
          <Typography variant="h6" component="h1" sx={{ flexGrow: 1, fontWeight: 700 }}>
            {title}
          </Typography>
          <Stack direction="row" spacing={1.5} alignItems="center">
            <Chip label={roleLabel} color="primary" size="small" variant="outlined" />
            <Stack alignItems="flex-end" sx={{ display: { xs: 'none', sm: 'flex' } }}>
              <Typography variant="body2" fontWeight={600} lineHeight={1.1}>
                {user?.displayName ?? 'Administrator'}
              </Typography>
              {user?.email ? (
                <Typography variant="caption" color="text.secondary">
                  {user.email}
                </Typography>
              ) : null}
            </Stack>
            <Avatar
              {...(user?.photoUrl ? { src: user.photoUrl } : {})}
              alt={user?.displayName ?? 'Administrator'}
              sx={{ width: 34, height: 34 }}
            />
            <Tooltip title="Log out">
              <IconButton onClick={handleLogout} aria-label="Log out">
                <LogoutRoundedIcon />
              </IconButton>
            </Tooltip>
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
            '& .MuiDrawer-paper': { boxSizing: 'border-box', width: DRAWER_WIDTH },
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
              borderRight: (t) => `1px solid ${t.palette.divider}`,
              backgroundColor: '#FFFFFF',
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
        }}
      >
        <Toolbar />
        <Box sx={{ p: { xs: 2, md: 3 } }}>{children}</Box>
      </Box>
    </Box>
  );
}
