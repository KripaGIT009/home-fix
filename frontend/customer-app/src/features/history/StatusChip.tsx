import { Box } from '@mui/material';
import type { BookingStatus } from '@stores/bookingStore';
import { radius } from '@lib/theme';
import { STATUS_TONE_COLORS, bookingStatusLabel, bookingStatusTone } from './status';

/** A booking status as a small coloured pill with a leading dot. */
export function StatusChip({
  status,
  size = 'small',
}: {
  status: BookingStatus;
  size?: 'small' | 'medium';
}) {
  const colors = STATUS_TONE_COLORS[bookingStatusTone(status)];
  return (
    <Box
      component="span"
      sx={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 0.75,
        px: size === 'small' ? 1 : 1.25,
        py: size === 'small' ? 0.25 : 0.5,
        borderRadius: `${radius.pill}px`,
        bgcolor: colors.bg,
        color: colors.fg,
        fontSize: size === 'small' ? '0.75rem' : '0.8125rem',
        fontWeight: 700,
        lineHeight: 1.5,
        whiteSpace: 'nowrap',
      }}
    >
      <Box
        component="span"
        aria-hidden
        sx={{ width: 6, height: 6, borderRadius: '50%', bgcolor: colors.dot, flexShrink: 0 }}
      />
      {bookingStatusLabel(status)}
    </Box>
  );
}
