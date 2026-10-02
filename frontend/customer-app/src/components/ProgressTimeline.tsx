import type { ReactNode } from 'react';
import { Box, Stack, Typography } from '@mui/material';
import { keyframes } from '@mui/material/styles';
import CheckRoundedIcon from '@mui/icons-material/CheckRounded';
import CloseRoundedIcon from '@mui/icons-material/CloseRounded';
import { brand, visuallyHidden } from '@lib/theme';

export type TimelineStepState = 'done' | 'current' | 'upcoming' | 'failed';

export interface TimelineStep {
  key: string;
  label: string;
  description?: ReactNode;
  state: TimelineStepState;
}

const pulse = keyframes`
  0% { box-shadow: 0 0 0 0 rgba(34, 81, 209, 0.35); }
  70% { box-shadow: 0 0 0 8px rgba(34, 81, 209, 0); }
  100% { box-shadow: 0 0 0 0 rgba(34, 81, 209, 0); }
`;

/**
 * A vertical progress timeline: completed steps carry a check, the current one
 * pulses, upcoming ones are hollow, and a stopped step (cancelled, no pro
 * found) is marked in amber — red stays reserved for emergencies. Used by
 * live tracking and the booking detail screen.
 */
export function ProgressTimeline({
  steps,
  'aria-label': ariaLabel = 'Booking progress',
}: {
  steps: TimelineStep[];
  'aria-label'?: string;
}) {
  return (
    <Box component="ol" aria-label={ariaLabel} sx={{ listStyle: 'none', m: 0, p: 0 }}>
      {steps.map((step, index) => {
        const isLast = index === steps.length - 1;
        const nextState = steps[index + 1]?.state;
        const connectorDone = step.state === 'done' && nextState !== 'upcoming';
        return (
          <Box
            component="li"
            key={step.key}
            aria-current={step.state === 'current' ? 'step' : undefined}
            sx={{ display: 'flex', gap: 1.75, position: 'relative' }}
          >
            <Stack alignItems="center" sx={{ flexShrink: 0, width: 24 }}>
              <StepMarker state={step.state} />
              {isLast ? null : (
                <Box
                  aria-hidden
                  sx={{
                    flexGrow: 1,
                    width: 2,
                    minHeight: 20,
                    my: 0.5,
                    borderRadius: 1,
                    bgcolor: connectorDone ? 'primary.main' : brand.line,
                  }}
                />
              )}
            </Stack>
            <Box sx={{ pb: isLast ? 0 : 2.25, pt: 0.125, minWidth: 0 }}>
              <Typography
                variant="subtitle2"
                sx={{
                  color:
                    step.state === 'upcoming'
                      ? 'text.secondary'
                      : step.state === 'failed'
                        ? brand.warmDark
                        : 'text.primary',
                  fontWeight: step.state === 'current' ? 700 : 600,
                }}
              >
                {step.label}
                <Box component="span" sx={visuallyHidden}>
                  {STATE_LABEL[step.state]}
                </Box>
              </Typography>
              {step.description ? (
                <Typography variant="body2" color="text.secondary" component="div">
                  {step.description}
                </Typography>
              ) : null}
            </Box>
          </Box>
        );
      })}
    </Box>
  );
}

const STATE_LABEL: Record<TimelineStepState, string> = {
  done: ' (done)',
  current: ' (current)',
  upcoming: ' (upcoming)',
  failed: ' (stopped)',
};

function StepMarker({ state }: { state: TimelineStepState }) {
  const base = {
    width: 24,
    height: 24,
    borderRadius: '50%',
    display: 'grid',
    placeItems: 'center',
    flexShrink: 0,
  } as const;

  if (state === 'done') {
    return (
      <Box aria-hidden sx={{ ...base, bgcolor: 'primary.main', color: 'common.white' }}>
        <CheckRoundedIcon sx={{ fontSize: 16 }} />
      </Box>
    );
  }
  if (state === 'failed') {
    return (
      <Box aria-hidden sx={{ ...base, bgcolor: brand.warmSoft, color: brand.warmDark }}>
        <CloseRoundedIcon sx={{ fontSize: 16 }} />
      </Box>
    );
  }
  if (state === 'current') {
    return (
      <Box
        aria-hidden
        sx={{
          ...base,
          bgcolor: brand.accentSoft,
          border: `2px solid ${brand.accent}`,
          animation: `${pulse} 1.8s ease-out infinite`,
        }}
      >
        <Box sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: 'primary.main' }} />
      </Box>
    );
  }
  return (
    <Box aria-hidden sx={{ ...base, border: `2px solid ${brand.lineStrong}`, bgcolor: '#fff' }} />
  );
}
