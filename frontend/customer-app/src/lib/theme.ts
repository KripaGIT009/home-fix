import { createTheme, alpha } from '@mui/material/styles';

/**
 * HomeFix Customer design system.
 *
 * One typeface (Plus Jakarta Sans, self-hosted via @fontsource so the app works
 * offline and inside the Capacitor shell), a trustworthy blue primary, a single
 * warm marigold accent for highlights such as ratings and "24×7", green for
 * verification and success, and red reserved strictly for the emergency path
 * and destructive actions.
 *
 * Screens compose these tokens rather than hard-coding colours, radii or
 * shadows, so the whole app restyles from this one file.
 */

/** Brand palette shared by the app shell, screens and illustrations. */
export const brand = {
  /** Primary blue — actions, links, focus. */
  accent: '#2251D1',
  accentDark: '#1A3FA6',
  accentDeep: '#0F2A6E',
  accentSoft: '#EEF2FD',
  accentLine: '#D6E0FA',
  /** Warm accent — highlights, ratings, 24×7. */
  warm: '#F2A516',
  warmDark: '#8A5300',
  warmSoft: '#FFF6E2',
  /** Text. */
  ink: '#101828',
  body: '#344054',
  muted: '#5F6B7E',
  subtle: '#98A2B3',
  /** Surfaces. */
  line: '#E4E7EC',
  lineStrong: '#D0D5DD',
  canvas: '#F7F8FA',
  paper: '#FFFFFF',
  /** Status. */
  amber: '#F2A516',
  green: '#12875A',
  greenSoft: '#EAF7F0',
  red: '#D92D20',
  redDark: '#B42318',
  redSoft: '#FEF3F2',
  slateSoft: '#F2F4F7',
} as const;

/** Corner radii, in px. Use these rather than numeric sx radii. */
export const radius = {
  xs: 6,
  sm: 8,
  md: 12,
  lg: 16,
  xl: 24,
  pill: 999,
} as const;

/** Soft, layered elevations. */
export const shadows = {
  xs: '0 1px 2px rgba(16, 24, 40, 0.05)',
  card: '0 1px 2px rgba(16, 24, 40, 0.04), 0 4px 12px -4px rgba(16, 24, 40, 0.06)',
  raised: '0 2px 4px rgba(16, 24, 40, 0.04), 0 12px 32px -8px rgba(16, 24, 40, 0.14)',
  overlay: '0 24px 48px -12px rgba(16, 24, 40, 0.22)',
  bar: '0 -1px 0 rgba(16, 24, 40, 0.06), 0 -8px 24px -12px rgba(16, 24, 40, 0.10)',
  focus: `0 0 0 4px ${alpha('#2251D1', 0.16)}`,
} as const;

/** Layout constants shared by the shell and full-bleed sections. */
export const layout = {
  /** Max width of page content on desktop. */
  maxWidth: 1200,
  /** Max width of a single reading/form column. */
  readingWidth: 760,
  /** Horizontal gutters by breakpoint, in theme spacing units. */
  gutter: { xs: 2, sm: 3, md: 4 },
  /** Height of the mobile bottom tab bar, in px. */
  bottomNavHeight: 68,
} as const;

const fontFamily =
  '"Plus Jakarta Sans Variable", "Plus Jakarta Sans", "Segoe UI", system-ui, -apple-system, Roboto, "Helvetica Neue", Arial, sans-serif';

const md = '@media (min-width:900px)';

export const theme = createTheme({
  palette: {
    mode: 'light',
    primary: {
      main: brand.accent,
      dark: brand.accentDark,
      light: '#5B7FE6',
      contrastText: '#FFFFFF',
    },
    secondary: { main: brand.warm, dark: brand.warmDark, contrastText: '#3B2400' },
    success: { main: brand.green, light: brand.greenSoft, contrastText: '#FFFFFF' },
    warning: {
      main: '#DC8A00',
      light: brand.warmSoft,
      dark: brand.warmDark,
      contrastText: '#3B2400',
    },
    error: { main: brand.red, light: brand.redSoft, dark: brand.redDark, contrastText: '#FFFFFF' },
    info: { main: brand.accent, light: brand.accentSoft },
    background: { default: brand.canvas, paper: brand.paper },
    text: { primary: brand.ink, secondary: brand.muted, disabled: brand.subtle },
    divider: brand.line,
    action: { hover: alpha(brand.ink, 0.04), selected: brand.accentSoft },
  },

  shape: { borderRadius: radius.md },

  typography: {
    fontFamily,
    htmlFontSize: 16,
    fontSize: 15,
    h1: {
      fontSize: '2.125rem',
      fontWeight: 800,
      letterSpacing: '-0.03em',
      lineHeight: 1.12,
      [md]: { fontSize: '3.25rem' },
    },
    h2: {
      fontSize: '1.625rem',
      fontWeight: 800,
      letterSpacing: '-0.025em',
      lineHeight: 1.2,
      [md]: { fontSize: '2.25rem' },
    },
    h3: {
      fontSize: '1.375rem',
      fontWeight: 750,
      letterSpacing: '-0.02em',
      lineHeight: 1.25,
      [md]: { fontSize: '1.75rem' },
    },
    h4: {
      fontSize: '1.25rem',
      fontWeight: 750,
      letterSpacing: '-0.02em',
      lineHeight: 1.3,
      [md]: { fontSize: '1.5rem' },
    },
    h5: { fontSize: '1.125rem', fontWeight: 700, letterSpacing: '-0.01em', lineHeight: 1.35 },
    h6: { fontSize: '1rem', fontWeight: 700, letterSpacing: '-0.005em', lineHeight: 1.4 },
    subtitle1: { fontSize: '1rem', fontWeight: 600, lineHeight: 1.45 },
    subtitle2: { fontSize: '0.875rem', fontWeight: 600, lineHeight: 1.45 },
    body1: { fontSize: '1rem', lineHeight: 1.6 },
    body2: { fontSize: '0.875rem', lineHeight: 1.55 },
    caption: { fontSize: '0.8125rem', lineHeight: 1.45 },
    button: { fontWeight: 650, letterSpacing: 0, textTransform: 'none' },
    overline: { fontSize: '0.75rem', fontWeight: 700, letterSpacing: '0.08em', lineHeight: 1.6 },
  },

  components: {
    MuiCssBaseline: {
      styleOverrides: {
        body: { backgroundColor: brand.canvas, color: brand.ink },
        '::selection': { backgroundColor: alpha(brand.accent, 0.18) },
        '*::-webkit-scrollbar': { width: 8, height: 8 },
        '*::-webkit-scrollbar-thumb': {
          background: alpha(brand.ink, 0.16),
          borderRadius: 4,
        },
      },
    },

    MuiButton: {
      defaultProps: { disableElevation: true },
      styleOverrides: {
        root: {
          textTransform: 'none',
          fontWeight: 650,
          borderRadius: radius.md,
          '&.Mui-focusVisible': { boxShadow: shadows.focus },
        },
        sizeLarge: { padding: '13px 22px', fontSize: '1rem' },
        sizeMedium: { padding: '9px 18px', fontSize: '0.9375rem' },
        sizeSmall: { padding: '6px 12px', fontSize: '0.8125rem' },
        containedPrimary: {
          backgroundColor: brand.accent,
          '&:hover': { backgroundColor: brand.accentDark },
        },
        outlined: {
          borderColor: brand.lineStrong,
          backgroundColor: brand.paper,
          '&:hover': { borderColor: brand.subtle, backgroundColor: brand.paper },
        },
        outlinedPrimary: {
          borderColor: brand.accentLine,
          '&:hover': { borderColor: brand.accent, backgroundColor: brand.accentSoft },
        },
        text: { '&:hover': { backgroundColor: alpha(brand.accent, 0.06) } },
      },
    },

    MuiIconButton: {
      styleOverrides: {
        root: { '&.Mui-focusVisible': { boxShadow: shadows.focus } },
      },
    },

    MuiCard: {
      defaultProps: { elevation: 0 },
      styleOverrides: {
        root: {
          borderRadius: radius.lg,
          border: `1px solid ${brand.line}`,
          boxShadow: shadows.xs,
          backgroundImage: 'none',
        },
      },
    },
    MuiCardContent: {
      styleOverrides: {
        root: {
          padding: 20,
          '&:last-child': { paddingBottom: 20 },
          [md]: { padding: 24, '&:last-child': { paddingBottom: 24 } },
        },
      },
    },
    MuiCardActionArea: {
      styleOverrides: {
        root: { borderRadius: radius.lg },
        focusHighlight: { borderRadius: radius.lg },
      },
    },

    MuiPaper: {
      styleOverrides: { rounded: { borderRadius: radius.lg } },
    },

    MuiAppBar: {
      defaultProps: { elevation: 0, color: 'inherit' },
      styleOverrides: {
        root: {
          backgroundColor: alpha('#FFFFFF', 0.92),
          backdropFilter: 'saturate(180%) blur(12px)',
          color: brand.ink,
          borderBottom: `1px solid ${brand.line}`,
          backgroundImage: 'none',
        },
      },
    },
    MuiToolbar: {
      styleOverrides: { root: { minHeight: 60, '@media (min-width:600px)': { minHeight: 68 } } },
    },

    MuiOutlinedInput: {
      styleOverrides: {
        root: {
          borderRadius: radius.md,
          backgroundColor: '#FFFFFF',
          '& .MuiOutlinedInput-notchedOutline': { borderColor: brand.lineStrong },
          '&:hover .MuiOutlinedInput-notchedOutline': { borderColor: brand.subtle },
          '&.Mui-focused': { boxShadow: shadows.focus },
          '&.Mui-focused .MuiOutlinedInput-notchedOutline': {
            borderWidth: 1,
            borderColor: brand.accent,
          },
          '&.Mui-error.Mui-focused': { boxShadow: `0 0 0 4px ${alpha(brand.red, 0.12)}` },
        },
        input: { '&::placeholder': { color: brand.subtle, opacity: 1 } },
      },
    },
    MuiInputLabel: { styleOverrides: { root: { fontSize: '0.9375rem' } } },
    MuiFormHelperText: { styleOverrides: { root: { marginLeft: 2, fontSize: '0.8125rem' } } },

    MuiChip: {
      styleOverrides: {
        root: { borderRadius: radius.sm, fontWeight: 600, fontSize: '0.8125rem' },
        sizeSmall: { height: 24, fontSize: '0.75rem' },
        outlined: { borderColor: brand.lineStrong },
        icon: { marginLeft: 6 },
      },
    },

    MuiAlert: {
      styleOverrides: {
        root: { borderRadius: radius.md, fontSize: '0.875rem', alignItems: 'flex-start' },
        icon: { paddingTop: 9 },
        message: { paddingTop: 9 },
        standardError: { backgroundColor: brand.redSoft, color: brand.redDark },
        standardSuccess: { backgroundColor: brand.greenSoft, color: '#05603A' },
        standardWarning: { backgroundColor: brand.warmSoft, color: brand.warmDark },
        standardInfo: { backgroundColor: brand.accentSoft, color: brand.accentDeep },
      },
    },

    MuiDivider: { styleOverrides: { root: { borderColor: brand.line } } },

    MuiListItemButton: {
      styleOverrides: {
        root: {
          borderRadius: radius.md,
          '&.Mui-selected': { backgroundColor: brand.accentSoft },
        },
      },
    },

    MuiBottomNavigation: {
      styleOverrides: {
        root: {
          height: layout.bottomNavHeight,
          backgroundColor: alpha('#FFFFFF', 0.96),
          backdropFilter: 'saturate(180%) blur(12px)',
        },
      },
    },
    MuiBottomNavigationAction: {
      styleOverrides: {
        root: {
          paddingTop: 10,
          color: brand.muted,
          minWidth: 64,
          '&.Mui-selected': { color: brand.accent },
        },
        label: {
          fontSize: '0.75rem',
          fontWeight: 600,
          marginTop: 2,
          '&.Mui-selected': { fontSize: '0.75rem', fontWeight: 700 },
        },
      },
    },

    MuiLinearProgress: { styleOverrides: { root: { borderRadius: 999, height: 6 } } },

    MuiAvatar: { styleOverrides: { root: { fontWeight: 700 } } },

    MuiSkeleton: {
      defaultProps: { animation: 'wave' },
      styleOverrides: { root: { backgroundColor: alpha(brand.ink, 0.06) } },
    },

    MuiTabs: {
      styleOverrides: { indicator: { height: 3, borderRadius: 3 } },
    },
    MuiTab: {
      styleOverrides: { root: { textTransform: 'none', fontWeight: 600, minHeight: 44 } },
    },

    MuiTooltip: {
      styleOverrides: {
        tooltip: { borderRadius: radius.sm, fontSize: '0.75rem', backgroundColor: brand.ink },
      },
    },

    MuiAccordion: {
      styleOverrides: {
        root: {
          border: `1px solid ${brand.line}`,
          borderRadius: radius.md,
          boxShadow: 'none',
          '&::before': { display: 'none' },
          '&.Mui-expanded': { margin: 0 },
          '&:first-of-type': { borderRadius: radius.md },
          '&:last-of-type': { borderRadius: radius.md },
        },
      },
    },

    MuiPaginationItem: {
      styleOverrides: { root: { fontWeight: 600, borderRadius: radius.sm } },
    },

    MuiSwitch: {
      styleOverrides: { root: { marginRight: 4 } },
    },
  },
});

/** Reusable surface treatments screens can spread into `sx`. */
export const surfaces = {
  /** Tinted panel used for summaries and inline callouts. */
  tint: {
    backgroundColor: brand.accentSoft,
    border: `1px solid ${brand.accentLine}`,
    borderRadius: `${radius.lg}px`,
  },
  /** Neutral sunken panel for secondary information. */
  sunken: {
    backgroundColor: brand.slateSoft,
    borderRadius: `${radius.md}px`,
  },
  /** Elevated sheet used for sticky action bars at the bottom of a screen. */
  actionBar: {
    position: 'sticky' as const,
    bottom: 0,
    backgroundColor: '#FFFFFF',
    borderTop: `1px solid ${brand.line}`,
    boxShadow: shadows.bar,
  },
} as const;

/** Soft blue wash used behind heroes and brand panels. */
export const heroBackground = `radial-gradient(1200px 420px at 85% -10%, ${alpha(
  brand.accent,
  0.14,
)} 0%, transparent 60%), radial-gradient(800px 360px at 0% 0%, ${alpha(
  brand.warm,
  0.1,
)} 0%, transparent 55%), linear-gradient(180deg, #F3F6FE 0%, ${brand.canvas} 100%)`;

/** Deep brand gradient for the sign-in panel and splash. */
export const brandGradient = `radial-gradient(900px 500px at 10% 0%, ${alpha(
  '#5B7FE6',
  0.45,
)} 0%, transparent 60%), linear-gradient(155deg, ${brand.accent} 0%, ${brand.accentDark} 45%, ${brand.accentDeep} 100%)`;

/** Hides content visually while keeping it available to screen readers. */
export const visuallyHidden = {
  position: 'absolute' as const,
  // Strings on purpose: in `sx`, a bare 1 means 100%.
  width: '1px',
  height: '1px',
  padding: 0,
  margin: '-1px',
  overflow: 'hidden' as const,
  clip: 'rect(0 0 0 0)',
  whiteSpace: 'nowrap' as const,
  border: 0,
};
