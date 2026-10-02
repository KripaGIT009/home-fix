import {
  useCallback,
  useEffect,
  useRef,
  type ClipboardEvent,
  type KeyboardEvent,
  type ChangeEvent,
  type FocusEvent,
} from 'react';
import { Box } from '@mui/material';
import { alpha } from '@mui/material/styles';
import { brand, radius } from '@lib/theme';

interface OtpInputProps {
  value: string;
  onChange: (value: string) => void;
  /** Called once every box holds a digit. */
  onComplete?: (value: string) => void;
  length: number;
  disabled?: boolean;
  error?: boolean;
  /** Move focus to the first box on mount (the step it replaces is gone). */
  focusOnMount?: boolean;
  /** Id of the element describing the field (helper or error text). */
  describedBy?: string;
  onBlur?: () => void;
}

/**
 * A one-box-per-digit code input.
 *
 * Typing advances to the next box, Backspace on an empty box steps back, the
 * arrow keys move between boxes, and pasting (or an SMS autofill that drops the
 * whole code into the first box) spreads the digits across the boxes. The
 * first box carries `autocomplete="one-time-code"` so iOS/Android offer the
 * code from the SMS.
 */
export function OtpInput({
  value,
  onChange,
  onComplete,
  length,
  disabled = false,
  error = false,
  focusOnMount = false,
  describedBy,
  onBlur,
}: OtpInputProps) {
  const refs = useRef<Array<HTMLInputElement | null>>([]);
  const digits = Array.from({ length }, (_, index) => value[index] ?? '');

  useEffect(() => {
    if (focusOnMount) refs.current[0]?.focus();
  }, [focusOnMount]);

  const focusBox = (index: number) => {
    const target = refs.current[Math.max(0, Math.min(length - 1, index))];
    target?.focus();
    target?.select();
  };

  const commit = useCallback(
    (next: string) => {
      const clean = next.replace(/\D/g, '').slice(0, length);
      onChange(clean);
      if (clean.length === length) onComplete?.(clean);
    },
    [length, onChange, onComplete],
  );

  /** Writes `incoming` digits starting at `index`, then moves focus after them. */
  const fillFrom = (index: number, incoming: string) => {
    const chars = incoming.replace(/\D/g, '');
    if (!chars) return;
    const next = digits.slice();
    for (let i = 0; i < chars.length && index + i < length; i += 1) {
      next[index + i] = chars[i] ?? '';
    }
    // Keep the code contiguous: drop anything after the first gap.
    const firstGap = next.indexOf('');
    const joined = (firstGap === -1 ? next : next.slice(0, firstGap)).join('');
    commit(joined);
    focusBox(Math.min(index + chars.length, length - 1));
  };

  const handleChange = (index: number) => (event: ChangeEvent<HTMLInputElement>) => {
    const raw = event.target.value;
    if (raw === '') {
      commit(digits.slice(0, index).join(''));
      return;
    }
    // A box already holding a digit receives the old + new character; keep the new one.
    const incoming = raw.length > 1 && digits[index] ? raw.replace(digits[index] ?? '', '') : raw;
    fillFrom(Math.min(index, value.length), incoming);
  };

  const handleKeyDown = (index: number) => (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'Backspace') {
      event.preventDefault();
      if (digits[index]) {
        commit(digits.slice(0, index).join(''));
      } else if (index > 0) {
        commit(digits.slice(0, index - 1).join(''));
        focusBox(index - 1);
      }
    } else if (event.key === 'ArrowLeft') {
      event.preventDefault();
      focusBox(index - 1);
    } else if (event.key === 'ArrowRight') {
      event.preventDefault();
      focusBox(Math.min(index + 1, value.length));
    }
  };

  const handlePaste = (index: number) => (event: ClipboardEvent<HTMLInputElement>) => {
    event.preventDefault();
    fillFrom(Math.min(index, value.length), event.clipboardData.getData('text'));
  };

  return (
    <Box
      role="group"
      aria-label="Verification code"
      sx={{
        display: 'grid',
        gridTemplateColumns: `repeat(${length}, minmax(0, 1fr))`,
        gap: { xs: 1, sm: 1.25 },
      }}
    >
      {digits.map((digit, index) => (
        <Box
          key={index}
          component="input"
          ref={(element: HTMLInputElement | null) => {
            refs.current[index] = element;
          }}
          value={digit}
          onChange={handleChange(index)}
          onKeyDown={handleKeyDown(index)}
          onPaste={handlePaste(index)}
          onFocus={(event: FocusEvent<HTMLInputElement>) => event.target.select()}
          onBlur={onBlur}
          disabled={disabled}
          type="text"
          inputMode="numeric"
          pattern="[0-9]*"
          autoComplete={index === 0 ? 'one-time-code' : 'off'}
          aria-label={`Digit ${index + 1} of ${length}`}
          aria-invalid={error || undefined}
          aria-describedby={describedBy}
          sx={{
            width: '100%',
            minWidth: 0,
            height: { xs: 52, sm: 58 },
            p: 0,
            textAlign: 'center',
            fontFamily: 'inherit',
            fontSize: { xs: '1.375rem', sm: '1.5rem' },
            fontWeight: 700,
            color: 'text.primary',
            bgcolor: disabled ? brand.slateSoft : '#FFFFFF',
            border: `1.5px solid ${error ? brand.red : digit ? brand.accentLine : brand.lineStrong}`,
            borderRadius: `${radius.md}px`,
            outline: 'none',
            caretColor: brand.accent,
            transition: 'border-color .15s, box-shadow .15s',
            '&:focus': {
              borderColor: error ? brand.red : brand.accent,
              boxShadow: `0 0 0 4px ${alpha(error ? brand.red : brand.accent, 0.14)}`,
            },
            '&:disabled': { color: 'text.disabled' },
          }}
        />
      ))}
    </Box>
  );
}
