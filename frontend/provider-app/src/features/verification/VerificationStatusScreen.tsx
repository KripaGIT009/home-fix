import {
  Alert,
  Card,
  CardContent,
  Chip,
  List,
  ListItem,
  ListItemIcon,
  ListItemText,
  Stack,
  Step,
  StepLabel,
  Stepper,
  Typography,
} from '@mui/material';
import CheckCircleRoundedIcon from '@mui/icons-material/CheckCircleRounded';
import RadioButtonUncheckedRoundedIcon from '@mui/icons-material/RadioButtonUncheckedRounded';
import ArrowRightRoundedIcon from '@mui/icons-material/ArrowRightRounded';
import { AppShell } from '@components/AppShell';
import { QueryStateView } from '@components/QueryStateView';
import { formatDateTime } from '@lib/format';
import { useVerificationState } from './hooks';
import {
  activeStepIndex,
  describeVerificationStatus,
  documentLabel,
  nextSteps,
  VERIFICATION_STEPS,
} from './status';
import type { VerificationState } from './api';

/**
 * Verification status screen (Requirement 5, 28.8): shows the Provider's
 * current verification state, a progress stepper through the verification
 * state machine, the document checklist, and the required next steps.
 */
export function VerificationStatusScreen() {
  const verification = useVerificationState();

  return (
    <AppShell title="Verification">
      <QueryStateView
        isLoading={verification.isLoading}
        isError={verification.isError}
        error={verification.error}
        onRetry={() => void verification.refetch()}
      >
        {verification.data ? <VerificationContent state={verification.data} /> : null}
      </QueryStateView>
    </AppShell>
  );
}

function VerificationContent({ state }: { state: VerificationState }) {
  const descriptor = describeVerificationStatus(state.status);
  const stepIndex = activeStepIndex(state.status);
  const steps = nextSteps(state);
  const isBranchState = stepIndex < 0;

  return (
    <Stack spacing={2}>
      <Card variant="outlined">
        <CardContent>
          <Stack direction="row" justifyContent="space-between" alignItems="center" spacing={1}>
            <Typography variant="subtitle1" fontWeight={700}>
              Current status
            </Typography>
            <Chip
              label={descriptor.label}
              color={
                descriptor.severity === 'success'
                  ? 'success'
                  : descriptor.severity === 'error'
                    ? 'error'
                    : descriptor.severity === 'warning'
                      ? 'warning'
                      : 'info'
              }
            />
          </Stack>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
            {descriptor.description}
          </Typography>
          <Typography variant="caption" color="text.secondary" sx={{ mt: 1, display: 'block' }}>
            Last updated {formatDateTime(state.updatedAt)}
          </Typography>
        </CardContent>
      </Card>

      {state.status === 'REJECTED' && state.rejectionReason ? (
        <Alert severity="error">Reason: {state.rejectionReason}</Alert>
      ) : null}

      {!isBranchState ? (
        <Card variant="outlined">
          <CardContent>
            <Typography variant="subtitle2" fontWeight={700} sx={{ mb: 2 }}>
              Progress
            </Typography>
            <Stepper activeStep={stepIndex} orientation="vertical">
              {VERIFICATION_STEPS.map((step) => (
                <Step key={step.status} completed={activeStepIndex(step.status) < stepIndex}>
                  <StepLabel>{step.label}</StepLabel>
                </Step>
              ))}
            </Stepper>
          </CardContent>
        </Card>
      ) : null}

      <Card variant="outlined">
        <CardContent>
          <Typography variant="subtitle2" fontWeight={700} sx={{ mb: 1 }}>
            Required documents
          </Typography>
          <List dense disablePadding>
            {state.requiredDocuments.map((doc) => (
              <ListItem key={doc.type} disableGutters>
                <ListItemIcon sx={{ minWidth: 36 }}>
                  {doc.submitted ? (
                    <CheckCircleRoundedIcon color="success" aria-label="Submitted" />
                  ) : (
                    <RadioButtonUncheckedRoundedIcon color="disabled" aria-label="Not submitted" />
                  )}
                </ListItemIcon>
                <ListItemText
                  primary={documentLabel(doc.type)}
                  secondary={doc.submitted ? 'Submitted' : 'Not submitted'}
                />
              </ListItem>
            ))}
          </List>
        </CardContent>
      </Card>

      <Card variant="outlined">
        <CardContent>
          <Typography variant="subtitle2" fontWeight={700} sx={{ mb: 1 }}>
            Next steps
          </Typography>
          <List dense disablePadding>
            {steps.map((step) => (
              <ListItem key={step} disableGutters alignItems="flex-start">
                <ListItemIcon sx={{ minWidth: 32, mt: 0.5 }}>
                  <ArrowRightRoundedIcon color="primary" aria-hidden />
                </ListItemIcon>
                <ListItemText primary={step} />
              </ListItem>
            ))}
          </List>
        </CardContent>
      </Card>
    </Stack>
  );
}
