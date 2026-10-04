import { Step, StepLabel, Stepper } from '@mui/material';

const STEPS = ['Your account', 'Agency details', 'HomeFix review'] as const;

interface AgencyStepsProps {
  /** 0 account, 1 agency details, 2 review (pending or decided). */
  active: 0 | 1 | 2;
  /** Marks the last step complete, once the application is decided. */
  decided?: boolean;
}

/**
 * Where an agency is in "Register your agency" (email-auth Requirement 5.1):
 * an account, then the agency's details, then HomeFix's decision. Shown on
 * both the sign-up page and the application page, which together are the flow.
 */
export function AgencySteps({ active, decided = false }: AgencyStepsProps) {
  return (
    <Stepper activeStep={decided ? STEPS.length : active} alternativeLabel sx={{ mb: 3 }}>
      {STEPS.map((label) => (
        <Step key={label}>
          <StepLabel>{label}</StepLabel>
        </Step>
      ))}
    </Stepper>
  );
}
