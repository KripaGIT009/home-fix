import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Simple 1-second countdown timer. Used for the OTP expiry window
 * (Requirement 1.4) and for the lockout remaining-time display
 * (Requirement 1.3).
 *
 * Returns the remaining seconds and a `reset` function to (re)start the
 * countdown from a new duration. The interval is cleared on unmount and when
 * the counter reaches zero.
 */
export function useCountdown(initialSeconds = 0): {
  secondsLeft: number;
  isRunning: boolean;
  reset: (seconds: number) => void;
} {
  const [secondsLeft, setSecondsLeft] = useState(initialSeconds);
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const clear = useCallback(() => {
    if (intervalRef.current !== null) {
      clearInterval(intervalRef.current);
      intervalRef.current = null;
    }
  }, []);

  const start = useCallback(
    (seconds: number) => {
      clear();
      setSecondsLeft(seconds);
      if (seconds <= 0) {
        return;
      }
      intervalRef.current = setInterval(() => {
        setSecondsLeft((prev) => {
          if (prev <= 1) {
            clear();
            return 0;
          }
          return prev - 1;
        });
      }, 1000);
    },
    [clear],
  );

  useEffect(() => clear, [clear]);

  return { secondsLeft, isRunning: secondsLeft > 0, reset: start };
}
