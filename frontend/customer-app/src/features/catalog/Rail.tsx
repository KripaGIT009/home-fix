import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { Box, IconButton } from '@mui/material';
import ChevronLeftRoundedIcon from '@mui/icons-material/ChevronLeftRounded';
import ChevronRightRoundedIcon from '@mui/icons-material/ChevronRightRounded';
import { brand, layout } from '@lib/theme';

/**
 * Tracks whether a horizontal scroller can move further either way, so the
 * arrow buttons only appear when there is something to scroll to. A rail
 * whose cards all fit (most categories have two services) shows no arrows.
 */
function useRailScroll() {
  const ref = useRef<HTMLDivElement | null>(null);
  const [edges, setEdges] = useState({ canPrev: false, canNext: false });

  useEffect(() => {
    const el = ref.current;
    if (!el) return undefined;
    const update = () => {
      const canPrev = el.scrollLeft > 4;
      const canNext = el.scrollLeft + el.clientWidth < el.scrollWidth - 4;
      setEdges((prev) =>
        prev.canPrev === canPrev && prev.canNext === canNext ? prev : { canPrev, canNext },
      );
    };
    update();
    el.addEventListener('scroll', update, { passive: true });
    const observer = new ResizeObserver(update);
    observer.observe(el);
    return () => {
      el.removeEventListener('scroll', update);
      observer.disconnect();
    };
  }, []);

  const scrollByPage = useCallback((direction: 1 | -1) => {
    const el = ref.current;
    if (!el) return;
    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    el.scrollBy({
      left: direction * el.clientWidth * 0.85,
      behavior: reduceMotion ? 'auto' : 'smooth',
    });
  }, []);

  return { ref, ...edges, scrollByPage };
}

/**
 * Negative margins matching the page gutter, so mobile rails bleed to the
 * screen edge; on desktop just enough slack for focus outlines.
 */
const bleed = { xs: -layout.gutter.xs, sm: -layout.gutter.sm, md: -0.75 } as const;
const inset = { xs: layout.gutter.xs, sm: layout.gutter.sm, md: 0.75 } as const;

interface RailProps {
  /** Accessible name for the list, e.g. "Plumbing services". */
  label: string;
  children: ReactNode;
  /** Gap between items, in theme spacing units. */
  gap?: { xs: number; md: number };
  /**
   * Vertical centre of the arrow buttons. Card rails centre them on the
   * square thumbnail rather than the whole card.
   */
  arrowTop?: string | number;
}

/**
 * A horizontal, scroll-snapping row that scrolls inside itself (never the
 * page). On mobile it is swiped and bleeds to the screen edges like a native
 * carousel; on desktop it gets previous/next buttons over its ends.
 *
 * Children are rendered as list items; give each a fixed width so short rails
 * stay left-aligned instead of stretching.
 */
export function Rail({ label, children, gap = { xs: 1.5, md: 2 }, arrowTop = '50%' }: RailProps) {
  const { ref, canPrev, canNext, scrollByPage } = useRailScroll();

  const arrow = (direction: 1 | -1) => {
    const visible = direction === 1 ? canNext : canPrev;
    return (
      <IconButton
        aria-label={direction === 1 ? `Scroll ${label} forward` : `Scroll ${label} back`}
        onClick={() => scrollByPage(direction)}
        // Faded rather than unmounted, so a focused arrow keeps focus at the end.
        tabIndex={visible ? 0 : -1}
        aria-hidden={!visible}
        sx={{
          display: { xs: 'none', md: 'inline-flex' },
          position: 'absolute',
          top: arrowTop,
          [direction === 1 ? 'right' : 'left']: -18,
          transform: 'translateY(-50%)',
          zIndex: 1,
          width: 36,
          height: 36,
          bgcolor: 'background.paper',
          color: 'text.primary',
          border: `1px solid ${brand.line}`,
          boxShadow: '0 4px 12px -2px rgba(15, 15, 15, 0.16)',
          opacity: visible ? 1 : 0,
          pointerEvents: visible ? 'auto' : 'none',
          transition: 'opacity .2s',
          '&:hover': { bgcolor: brand.slateSoft },
        }}
      >
        {direction === 1 ? <ChevronRightRoundedIcon /> : <ChevronLeftRoundedIcon />}
      </IconButton>
    );
  };

  return (
    <Box sx={{ position: 'relative' }}>
      {arrow(-1)}
      <Box
        ref={ref}
        role="list"
        aria-label={label}
        sx={{
          display: 'flex',
          gap,
          overflowX: 'auto',
          overscrollBehaviorX: 'contain',
          scrollSnapType: 'x mandatory',
          scrollbarWidth: 'none',
          '&::-webkit-scrollbar': { display: 'none' },
          // Room for focus rings and hover shadows, which overflow would clip.
          py: 0.75,
          my: -0.75,
          mx: bleed,
          px: inset,
          scrollPaddingInline: { xs: 16, sm: 24, md: 6 },
          '& > *': { scrollSnapAlign: 'start', flexShrink: 0 },
        }}
      >
        {children}
      </Box>
      {arrow(1)}
    </Box>
  );
}
