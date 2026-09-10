import { createTheme, alpha } from '@mui/material/styles';

/**
 * HomeFix Provider design system — mobile-first.
 *
 * Shares the Customer app's neutrals, typography and component shapes so both
 * apps feel like one product, but leads with teal rather than blue: a provider
 * switching between the two must never be in doubt about which app is open.
 * Amber marks ratings and money, green marks verification, and red is reserved
 * for urgent job states.
 *
 * Screens compose these tokens rather than hard-coding colours, radii or
 * shadows, so the whole app restyles from this one file.
 */

/** Brand palette shared by the app shell, screens and charts. */
export const brand = {
  accent: '#0F766E',
  accentDark: '#115E59',
  accentSoft: '#ECFDF5',
  ink: '#0F172A',
  muted: '#64748B',
  line: '#E6EBF2',
  canvas: '#F5F7FB',
  amber: '#F59E0B',
  green: '#16A34A',
  greenSoft: '#ECFDF3',
  red: '#DC2626',
  redSoft: '#FEF2F2',
} as const;

/** Soft, layered elevations — closer to a native app than MUI's defaults. */
const shadows = {
  card: '0 1px 2px rgba(15, 23, 42, 0.04), 0 8px 24px -12px rgba(15, 23, 42, 0.12)',
  raised: '0 2px 4px rgba(15, 23, 42, 0.05), 0 16px 32px -16px rgba(15, 23, 42, 0.20)',
  bar: '0 -1px 0 rgba(15, 23, 42, 0.06)',
} as const;

export const theme = createTheme({
  palette: {
    mode: 'light',
    primary: {
      main: brand.accent,
      dark: brand.accentDark,
      light: '#5EEAD4',
      contrastText: '#FFFFFF',
    },
    secondary: { main: brand.amber, contrastText: '#3B2400' },
    success: { main: brand.green, light: brand.greenSoft, contrastText: '#FFFFFF' },
    warning: { main: brand.amber, contrastText: '#3B2400' },
    error: { main: brand.red, light: brand.redSoft, contrastText: '#FFFFFF' },
    info: { main: brand.accent },
    background: { default: brand.canvas, paper: '#FFFFFF' },
    text: { primary: brand.ink, secondary: brand.muted },
    divider: brand.line,
  },

  shape: { borderRadius: 14 },

  typography: {
    fontFamily: '"Inter", "Segoe UI", "Roboto", "Helvetica Neue", Arial, sans-serif',
    h1: { fontSize: '2rem', fontWeight: 800, letterSpacing: '-0.02em' },
    h2: { fontSize: '1.75rem', fontWeight: 800, letterSpacing: '-0.02em' },
    h3: { fontSize: '1.5rem', fontWeight: 700, letterSpacing: '-0.02em' },
    h4: { fontSize: '1.375rem', fontWeight: 700, letterSpacing: '-0.02em', lineHeight: 1.3 },
    h5: { fontSize: '1.175rem', fontWeight: 700, letterSpacing: '-0.01em' },
    h6: { fontSize: '1rem', fontWeight: 700, letterSpacing: '-0.01em' },
    subtitle1: { fontSize: '0.9375rem', fontWeight: 600 },
    subtitle2: { fontSize: '0.8125rem', fontWeight: 600, color: brand.muted },
    body1: { fontSize: '0.9375rem', lineHeight: 1.55 },
    body2: { fontSize: '0.8125rem', lineHeight: 1.5 },
    caption: { fontSize: '0.75rem', lineHeight: 1.45 },
    button: { fontWeight: 600, letterSpacing: 0 },
    overline: { fontSize: '0.6875rem', fontWeight: 700, letterSpacing: '0.08em' },
  },

  components: {
    MuiCssBaseline: {
      styleOverrides: {
        body: { backgroundColor: brand.canvas },
        // Screens are thumb-scrolled lists; a hairline scrollbar keeps the
        // desktop preview from looking like a website in a phone frame.
        '*::-webkit-scrollbar': { width: 6, height: 6 },
        '*::-webkit-scrollbar-thumb': {
          background: alpha(brand.ink, 0.16),
          borderRadius: 3,
        },
      },
    },

    MuiButton: {
      defaultProps: { disableElevation: true },
      styleOverrides: {
        root: { textTransform: 'none', fontWeight: 600, borderRadius: 12 },
        sizeLarge: { padding: '12px 20px', fontSize: '0.9375rem' },
        sizeMedium: { padding: '9px 16px' },
        sizeSmall: { padding: '5px 12px' },
        containedPrimary: {
          boxShadow: '0 6px 16px -8px rgba(15, 118, 110, 0.9)',
          '&:hover': { boxShadow: '0 8px 20px -8px rgba(15, 118, 110, 0.9)' },
        },
        outlined: { borderColor: brand.line, '&:hover': { borderColor: '#CBD5E1' } },
      },
    },

    MuiCard: {
      defaultProps: { elevation: 0 },
      styleOverrides: {
        root: {
          borderRadius: 16,
          border: `1px solid ${brand.line}`,
          boxShadow: shadows.card,
          backgroundImage: 'none',
        },
      },
    },
    MuiCardContent: {
      styleOverrides: { root: { padding: 16, '&:last-child': { paddingBottom: 16 } } },
    },
    MuiCardActionArea: {
      styleOverrides: { root: { borderRadius: 16 } },
    },

    MuiPaper: {
      styleOverrides: { rounded: { borderRadius: 16 } },
    },

    MuiAppBar: {
      defaultProps: { elevation: 0, color: 'inherit' },
      styleOverrides: {
        root: {
          backgroundColor: '#FFFFFF',
          color: brand.ink,
          borderBottom: `1px solid ${brand.line}`,
          backgroundImage: 'none',
        },
      },
    },
    MuiToolbar: {
      styleOverrides: { root: { minHeight: 60, '@media (min-width:600px)': { minHeight: 64 } } },
    },

    MuiOutlinedInput: {
      styleOverrides: {
        root: {
          borderRadius: 12,
          backgroundColor: '#FFFFFF',
          '& .MuiOutlinedInput-notchedOutline': { borderColor: brand.line },
          '&:hover .MuiOutlinedInput-notchedOutline': { borderColor: '#C7D2E1' },
          '&.Mui-focused .MuiOutlinedInput-notchedOutline': { borderWidth: 1.5 },
        },
        input: { '&::placeholder': { color: '#94A3B8', opacity: 1 } },
      },
    },
    MuiInputLabel: { styleOverrides: { root: { fontSize: '0.9375rem' } } },
    MuiFormHelperText: { styleOverrides: { root: { marginLeft: 2, fontSize: '0.75rem' } } },

    MuiChip: {
      styleOverrides: {
        root: { borderRadius: 8, fontWeight: 600, fontSize: '0.75rem' },
        sizeSmall: { height: 24 },
        outlined: { borderColor: brand.line },
        icon: { marginLeft: 6 },
      },
    },

    MuiAlert: {
      styleOverrides: {
        root: { borderRadius: 12, fontSize: '0.875rem', alignItems: 'center' },
        standardError: { backgroundColor: brand.redSoft },
        standardSuccess: { backgroundColor: brand.greenSoft },
      },
    },

    MuiDivider: { styleOverrides: { root: { borderColor: brand.line } } },

    MuiListItemButton: {
      styleOverrides: {
        root: {
          borderRadius: 12,
          '&.Mui-selected': { backgroundColor: brand.accentSoft },
        },
      },
    },

    MuiBottomNavigation: {
      styleOverrides: {
        root: { height: 64, borderTop: `1px solid ${brand.line}`, boxShadow: shadows.bar },
      },
    },
    MuiBottomNavigationAction: {
      styleOverrides: {
        root: {
          paddingTop: 8,
          color: brand.muted,
          '&.Mui-selected': { color: brand.accent },
        },
        label: {
          fontSize: '0.6875rem',
          fontWeight: 600,
          '&.Mui-selected': { fontSize: '0.6875rem' },
        },
      },
    },

    MuiLinearProgress: { styleOverrides: { root: { borderRadius: 999, height: 6 } } },

    MuiAvatar: { styleOverrides: { root: { fontWeight: 700 } } },

    MuiStepIcon: { styleOverrides: { root: { '&.Mui-completed': { color: brand.green } } } },

    MuiTabs: {
      styleOverrides: {
        indicator: { height: 3, borderRadius: 3 },
      },
    },
    MuiTab: {
      styleOverrides: {
        root: { textTransform: 'none', fontWeight: 600, minHeight: 44 },
      },
    },

    MuiTooltip: {
      styleOverrides: {
        tooltip: { borderRadius: 8, fontSize: '0.75rem', backgroundColor: brand.ink },
      },
    },
  },
});

/** Reusable surface treatments screens can spread into `sx`. */
export const surfaces = {
  /** Tinted panel used for summaries and inline callouts. */
  tint: {
    backgroundColor: brand.accentSoft,
    border: `1px solid ${alpha(brand.accent, 0.16)}`,
    borderRadius: 3,
  },
  /** Elevated sheet used for sticky action bars at the bottom of a screen. */
  actionBar: {
    position: 'sticky' as const,
    bottom: 0,
    backgroundColor: '#FFFFFF',
    borderTop: `1px solid ${brand.line}`,
    boxShadow: shadows.raised,
  },
} as const;
