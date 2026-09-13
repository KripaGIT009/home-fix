import { createTheme, alpha } from '@mui/material/styles';

/**
 * HomeFix Admin design system — desktop console.
 *
 * Shares the Customer app's palette and component shapes so the platform reads
 * as one product, tuned for a dense operational console: smaller default type,
 * tighter table rows, and a flat card treatment that survives being tiled many
 * to a screen. Amber marks pending work, green approvals, red rejections and
 * SLA breaches.
 *
 * Screens compose these tokens rather than hard-coding colours, radii or
 * shadows, so the whole portal restyles from this one file.
 */

/** Brand palette shared by the app shell, screens and charts. */
export const brand = {
  accent: '#2563EB',
  accentDark: '#1D4ED8',
  accentSoft: '#EFF4FF',
  ink: '#0F172A',
  muted: '#64748B',
  /** One step darker than `muted`, for secondary text that must still pass AA. */
  subtle: '#475569',
  line: '#E6EBF2',
  /** Hairline used inside dense surfaces (table rows) where `line` reads heavy. */
  lineSoft: '#F1F5F9',
  canvas: '#F5F7FB',
  amber: '#F59E0B',
  amberSoft: '#FFF7ED',
  green: '#16A34A',
  greenSoft: '#ECFDF3',
  red: '#DC2626',
  redSoft: '#FEF2F2',

  /**
   * Navigation ramp. The sidebar is dark so the console reads as a tool rather
   * than a document: it anchors the left edge, pushes the white content region
   * forward, and keeps the accent available for meaning (selection, primary
   * actions) instead of spending it on chrome.
   */
  navBg: '#0B1220',
  navBgRaised: '#111C32',
  navText: '#94A3B8',
  navTextActive: '#FFFFFF',
  navLine: 'rgba(148, 163, 184, 0.14)',
} as const;

/** Soft, layered elevations — closer to a native app than MUI's defaults. */
const shadows = {
  card: '0 1px 2px rgba(15, 23, 42, 0.04), 0 8px 24px -12px rgba(15, 23, 42, 0.12)',
  raised: '0 2px 4px rgba(15, 23, 42, 0.05), 0 16px 32px -16px rgba(15, 23, 42, 0.20)',
  bar: '0 -1px 0 rgba(15, 23, 42, 0.06)',
  /** Menus and popovers: tight and dark enough to separate from a white page. */
  overlay: '0 4px 6px -2px rgba(15, 23, 42, 0.06), 0 24px 48px -16px rgba(15, 23, 42, 0.24)',
} as const;

export const theme = createTheme({
  palette: {
    mode: 'light',
    primary: {
      main: brand.accent,
      dark: brand.accentDark,
      light: '#60A5FA',
      contrastText: '#FFFFFF',
    },
    secondary: { main: brand.amber, contrastText: '#3B2400' },
    success: { main: brand.green, light: brand.greenSoft, contrastText: '#FFFFFF' },
    warning: { main: brand.amber, contrastText: '#3B2400' },
    error: { main: brand.red, light: brand.redSoft, contrastText: '#FFFFFF' },
    info: { main: brand.accent },
    background: { default: brand.canvas, paper: '#FFFFFF' },
    text: { primary: brand.ink, secondary: brand.subtle },
    divider: brand.line,
  },

  shape: { borderRadius: 12 },

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
          boxShadow: '0 6px 16px -8px rgba(37, 99, 235, 0.9)',
          '&:hover': { boxShadow: '0 8px 20px -8px rgba(37, 99, 235, 0.9)' },
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

    // Tables are where an operator spends the day, so they get explicit
    // treatment rather than MUI's defaults: a quiet tinted header that stays
    // put while the body scrolls, hairline row rules, and a hover band that
    // makes it obvious which row an action will apply to.
    MuiTableHead: {
      styleOverrides: {
        root: {
          '& .MuiTableCell-head': {
            backgroundColor: brand.canvas,
            color: brand.subtle,
            fontWeight: 700,
            fontSize: '0.75rem',
            letterSpacing: '0.04em',
            textTransform: 'uppercase',
            borderBottom: `1px solid ${brand.line}`,
            whiteSpace: 'nowrap',
          },
        },
      },
    },
    MuiTableCell: {
      styleOverrides: {
        root: {
          borderBottom: `1px solid ${brand.lineSoft}`,
          fontSize: '0.8125rem',
          paddingTop: 10,
          paddingBottom: 10,
        },
      },
    },
    MuiTableRow: {
      styleOverrides: {
        root: {
          '&:last-of-type .MuiTableCell-root': { borderBottom: 'none' },
          '&.MuiTableRow-hover:hover': { backgroundColor: brand.canvas },
        },
      },
    },
    MuiTableContainer: {
      styleOverrides: { root: { borderRadius: 12 } },
    },

    MuiMenu: {
      defaultProps: { elevation: 0 },
      styleOverrides: {
        paper: {
          borderRadius: 12,
          border: `1px solid ${brand.line}`,
          boxShadow: shadows.overlay,
          marginTop: 6,
          minWidth: 200,
        },
        list: { padding: 6 },
      },
    },
    MuiMenuItem: {
      styleOverrides: {
        root: {
          borderRadius: 8,
          fontSize: '0.875rem',
          minHeight: 38,
          gap: 10,
        },
      },
    },

    MuiDialog: {
      styleOverrides: { paper: { borderRadius: 16, boxShadow: shadows.overlay } },
    },
    MuiDialogTitle: {
      styleOverrides: { root: { fontSize: '1.0625rem', fontWeight: 700, paddingBottom: 8 } },
    },

    MuiSkeleton: {
      styleOverrides: { root: { borderRadius: 8, backgroundColor: brand.lineSoft } },
    },

    MuiIconButton: {
      styleOverrides: { root: { borderRadius: 10 } },
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
