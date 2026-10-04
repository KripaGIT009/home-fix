import { Box, Button, Typography } from '@mui/material';
import { formatDuration } from './lockout';

interface ResendCodeRowProps {
  onResend: () => void;
  /** Seconds until the Auth Service accepts another resend (60 s after each send). */
  secondsLeft: number;
  isResending: boolean;
  /** Disables the button while something else is in flight (e.g. verifying). */
  disabled?: boolean;
}

/**
 * "Didn't get it?" row under an emailed-code field: the resend button stays
 * disabled through the once-a-minute cooldown (email-auth Requirement 1.8) and
 * says how long is left.
 */
export function ResendCodeRow({
  onResend,
  secondsLeft,
  isResending,
  disabled = false,
}: ResendCodeRowProps) {
  const coolingDown = secondsLeft > 0;

  return (
    <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
      <Typography variant="body2" color="text.secondary" fontWeight={600} aria-live="polite">
        {coolingDown ? `New code in ${formatDuration(secondsLeft)}` : "Didn't get the code?"}
      </Typography>
      <Button
        type="button"
        size="small"
        onClick={onResend}
        disabled={coolingDown || isResending || disabled}
      >
        {isResending ? 'Sending…' : 'Resend code'}
      </Button>
    </Box>
  );
}
