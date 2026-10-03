import {
  Alert,
  Box,
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
import { AppShell } from '@components/AppShell';
import { QueryStateView } from '@components/QueryStateView';
import { formatDateTime } from '@lib/format';
import { DocumentUploadForm } from './DocumentUploadForm';
import { useVerificationState } from './hooks';
import {
  activeStepIndex,
  canUploadDocuments,
  describeVerificationStatus,
  documentLabel,
  VERIFICATION_STEPS,
} from './status';
import type { VerificationState } from './api';

/**
 * Verification status screen (Requirement 5, 28.8): the Provider's current
 * verification state explained in plain language, a progress stepper through
 * the verification state machine, and either the document upload form (while
 * documents are still needed) or the documents on file.
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
  const isBranchState = stepIndex < 0;
  const uploadAllowed = canUploadDocuments(state.status);
  const hasDocuments = state.requiredDocuments.some((doc) => doc.submitted);

  return (
    <Stack spacing={2}>
      <Card variant="outlined">
        <CardContent>
          <Stack direction="row" justifyContent="space-between" alignItems="center" spacing={1}>
            <Typography variant="subtitle1" fontWeight={700}>
              Current status
            </Typography>
            <Chip label={descriptor.label} color={descriptor.severity} />
          </Stack>
          <Typography variant="body2" sx={{ mt: 1 }}>
            {descriptor.description}
          </Typography>
          <Box sx={{ mt: 1.5, p: 1.5, borderRadius: 2, bgcolor: 'action.hover' }}>
            <Typography variant="caption" fontWeight={700} color="text.secondary">
              What happens next
            </Typography>
            <Typography variant="body2">{descriptor.next}</Typography>
          </Box>
          {state.updatedAt ? (
            <Typography variant="caption" color="text.secondary" sx={{ mt: 1, display: 'block' }}>
              Last updated {formatDateTime(state.updatedAt)}
            </Typography>
          ) : null}
        </CardContent>
      </Card>

      {state.status === 'REJECTED' && state.rejectionReason ? (
        <Alert severity="error">Reason: {state.rejectionReason}</Alert>
      ) : null}

      {uploadAllowed ? <DocumentUploadForm documents={state.requiredDocuments} /> : null}

      {!isBranchState ? (
        <Card variant="outlined">
          <CardContent>
            <Typography variant="subtitle2" fontWeight={700} sx={{ mb: 2 }}>
              How verification works
            </Typography>
            <Stepper activeStep={stepIndex} orientation="vertical">
              {VERIFICATION_STEPS.map((step, index) => (
                <Step
                  key={step.status}
                  completed={
                    index < stepIndex || (index === stepIndex && step.status === 'APPROVED')
                  }
                >
                  <StepLabel
                    optional={
                      <Typography variant="caption" color="text.secondary">
                        {step.caption}
                      </Typography>
                    }
                  >
                    {step.label}
                  </StepLabel>
                </Step>
              ))}
            </Stepper>
          </CardContent>
        </Card>
      ) : null}

      {!uploadAllowed && hasDocuments ? (
        <Card variant="outlined">
          <CardContent>
            <Typography variant="subtitle2" fontWeight={700} sx={{ mb: 1 }}>
              Documents on file
            </Typography>
            <List dense disablePadding>
              {state.requiredDocuments.map((doc) => (
                <ListItem key={doc.type} disableGutters>
                  <ListItemIcon sx={{ minWidth: 36 }}>
                    {doc.submitted ? (
                      <CheckCircleRoundedIcon color="success" aria-label="Received" />
                    ) : (
                      <RadioButtonUncheckedRoundedIcon color="disabled" aria-label="Not received" />
                    )}
                  </ListItemIcon>
                  <ListItemText
                    primary={documentLabel(doc.type)}
                    secondary={
                      doc.submitted
                        ? doc.uploadedAt
                          ? `Received ${formatDateTime(doc.uploadedAt)}`
                          : 'Received'
                        : 'Not received'
                    }
                  />
                </ListItem>
              ))}
            </List>
          </CardContent>
        </Card>
      ) : null}
    </Stack>
  );
}
