import { useRef, useState, type FormEvent } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { Box, IconButton, InputBase } from '@mui/material';
import SearchRoundedIcon from '@mui/icons-material/SearchRounded';
import CloseRoundedIcon from '@mui/icons-material/CloseRounded';
import { brand, radius, shadows } from '@lib/theme';

/** Route whose `?q=` the field drives; the home screen filters by it. */
const HOME_PATH = '/home';

/**
 * The service search box, shown in the desktop header on every screen and at
 * the top of the home screen on mobile.
 *
 * The query lives in the home URL (`/home?q=tap`) rather than in component
 * state, so the header field and the mobile field stay in step, a search
 * survives a reload, and Back leaves search instead of the page. On home it
 * filters live as you type; elsewhere, submitting takes you to home with the
 * query applied.
 */
export function ServiceSearchField({ variant = 'header' }: { variant?: 'header' | 'page' }) {
  const location = useLocation();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const [draft, setDraft] = useState('');
  const inputRef = useRef<HTMLInputElement | null>(null);

  const onHome = location.pathname === HOME_PATH;
  const value = onHome ? (params.get('q') ?? '') : draft;

  const update = (next: string) => {
    if (!onHome) {
      setDraft(next);
      return;
    }
    setParams(
      (prev) => {
        const updated = new URLSearchParams(prev);
        if (next) updated.set('q', next);
        else updated.delete('q');
        // Typing is the newer intent, so it replaces any other filter.
        updated.delete('view');
        return updated;
      },
      { replace: true },
    );
  };

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault();
    const query = value.trim();
    if (onHome) {
      // Results are already on screen; dismiss the on-screen keyboard.
      inputRef.current?.blur();
      return;
    }
    navigate(query ? `${HOME_PATH}?q=${encodeURIComponent(query)}` : HOME_PATH);
  };

  const page = variant === 'page';

  return (
    <Box
      component="form"
      role="search"
      onSubmit={handleSubmit}
      sx={{
        display: 'flex',
        alignItems: 'center',
        width: '100%',
        height: page ? 48 : 44,
        pl: 1.5,
        pr: 0.5,
        bgcolor: 'background.paper',
        border: `1px solid ${brand.lineStrong}`,
        borderRadius: `${radius.sm}px`,
        transition: 'border-color .2s, box-shadow .2s',
        '&:hover': { borderColor: brand.subtle },
        '&:focus-within': { borderColor: 'primary.main', boxShadow: shadows.focus },
      }}
    >
      <SearchRoundedIcon sx={{ color: 'text.secondary', fontSize: 20, mr: 1 }} aria-hidden />
      <InputBase
        inputRef={inputRef}
        value={value}
        onChange={(event) => update(event.target.value)}
        placeholder="Search for a service"
        inputProps={{ 'aria-label': 'Search for a service', enterKeyHint: 'search' }}
        sx={{
          flexGrow: 1,
          fontSize: '0.875rem',
          '& input::placeholder': { color: brand.muted, opacity: 1 },
        }}
      />
      {value ? (
        <IconButton aria-label="Clear search" onClick={() => update('')} size="small">
          <CloseRoundedIcon fontSize="small" />
        </IconButton>
      ) : null}
    </Box>
  );
}
