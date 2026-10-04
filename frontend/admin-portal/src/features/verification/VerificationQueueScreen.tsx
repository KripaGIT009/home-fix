import { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  Tab,
  Tabs,
  TextField,
  Typography,
} from '@mui/material';
import { ModuleScreen } from '@components/ModuleScreen';
import { QueryStateView } from '@components/QueryStateView';
import { DataTable, type Column } from '@components/DataTable';
import { formatDateTime } from '@lib/format';
import { DocumentViewer } from './DocumentViewer';
import {
  useBackgroundCheckDecision,
  useBackgroundChecks,
  useVerificationDecision,
  useVerificationDocuments,
  useVerificationQueue,
} from './hooks';
import type { BackgroundCheckEntry, VerificationQueueEntry } from './api';

type Step = 'documents' | 'background';

/**
 * Verification Queue module (Requirement 19.3, 5.4-5.8), in its two admin steps.
 *
 * **Document review** lists providers in DOCUMENT_SUBMITTED, oldest first,
 * and opens a review dialog where each submitted document is rendered inline
 * (PDFs in an embedded frame) without a separate download. Accepting the
 * documents starts the background check; rejecting needs a reason.
 *
 * **Background check** lists providers whose check has started. No
 * background-check vendor is integrated, so the admin runs the check and
 * records its result here, which approves the provider for jobs or rejects
 * them with a reason.
 */
export function VerificationQueueScreen() {
  const [step, setStep] = useState<Step>('documents');
  const backgroundQuery = useBackgroundChecks();
  const backgroundCount = backgroundQuery.data?.length ?? 0;

  return (
    <ModuleScreen
      title="Verification Queue"
      description="Review providers' documents, then record their background check to approve them for jobs."
    >
      <Box sx={{ borderBottom: 1, borderColor: 'divider', mb: 2 }}>
        <Tabs value={step} onChange={(_, next: Step) => setStep(next)}>
          <Tab value="documents" label="Document review" />
          <Tab
            value="background"
            label={backgroundCount ? `Background check (${backgroundCount})` : 'Background check'}
          />
        </Tabs>
      </Box>

      {step === 'documents' ? <DocumentReviewQueue /> : <BackgroundCheckQueue />}
    </ModuleScreen>
  );
}

function DocumentReviewQueue() {
  const queueQuery = useVerificationQueue();
  const [selected, setSelected] = useState<VerificationQueueEntry | null>(null);

  const columns: Column<VerificationQueueEntry>[] = [
    { key: 'name', header: 'Provider', render: (row) => row.displayName ?? '—' },
    { key: 'mobile', header: 'Mobile', render: (row) => row.mobileNumber ?? '—' },
    { key: 'skill', header: 'Primary skill', render: (row) => row.primarySkill ?? '—' },
    {
      key: 'submitted',
      header: 'Submitted',
      render: (row) => formatDateTime(row.submittedAt),
    },
    {
      key: 'docs',
      header: 'Documents',
      align: 'center',
      render: (row) => <Chip size="small" label={row.documentCount} />,
    },
    {
      key: 'action',
      header: '',
      align: 'right',
      render: () => (
        <Button size="small" variant="outlined">
          Review
        </Button>
      ),
    },
  ];

  return (
    <>
      <QueryStateView
        isLoading={queueQuery.isLoading}
        isError={queueQuery.isError}
        error={queueQuery.error}
        onRetry={() => void queueQuery.refetch()}
        isEmpty={(queueQuery.data?.length ?? 0) === 0}
        emptyMessage="No providers are waiting for document review."
      >
        <DataTable
          columns={columns}
          rows={queueQuery.data ?? []}
          rowKey={(row) => row.providerId}
          onRowClick={(row) => setSelected(row)}
        />
      </QueryStateView>

      {selected ? <ReviewDialog entry={selected} onClose={() => setSelected(null)} /> : null}
    </>
  );
}

interface ReviewDialogProps {
  entry: VerificationQueueEntry;
  onClose: () => void;
}

function ReviewDialog({ entry, onClose }: ReviewDialogProps) {
  const documentsQuery = useVerificationDocuments(entry.providerId);
  const decision = useVerificationDecision(entry.providerId);
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState('');

  const handleApprove = () => {
    decision.mutate({ decision: 'APPROVE' }, { onSuccess: onClose });
  };

  const handleReject = () => {
    if (!reason.trim()) {
      setRejecting(true);
      return;
    }
    decision.mutate({ decision: 'REJECT', reason: reason.trim() }, { onSuccess: onClose });
  };

  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>
        <Stack direction="row" justifyContent="space-between" alignItems="baseline" spacing={2}>
          <span>{entry.displayName ?? 'Provider submission'}</span>
          <Typography variant="caption" color="text.secondary">
            Submitted {formatDateTime(entry.submittedAt)}
          </Typography>
        </Stack>
      </DialogTitle>
      <DialogContent dividers>
        <QueryStateView
          isLoading={documentsQuery.isLoading}
          isError={documentsQuery.isError}
          error={documentsQuery.error}
          onRetry={() => void documentsQuery.refetch()}
        >
          <DocumentViewer documents={documentsQuery.data ?? []} />
        </QueryStateView>

        <Typography variant="body2" color="text.secondary" sx={{ mt: 2 }}>
          Accepting the documents starts the background check. The provider moves to the Background
          check tab, where you record its result to approve them for jobs.
        </Typography>

        {rejecting ? (
          <Box sx={{ mt: 2 }}>
            <TextField
              label="Rejection reason"
              value={reason}
              onChange={(event) => setReason(event.target.value)}
              fullWidth
              multiline
              minRows={2}
              required
              error={rejecting && !reason.trim()}
              helperText={
                rejecting && !reason.trim()
                  ? 'A reason is required to reject a submission.'
                  : 'Shared with the provider.'
              }
            />
          </Box>
        ) : null}

        {decision.isError ? (
          <Alert severity="error" sx={{ mt: 2 }}>
            {decision.error.message}
          </Alert>
        ) : null}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={decision.isPending}>
          Cancel
        </Button>
        <Button color="error" onClick={handleReject} disabled={decision.isPending}>
          Reject
        </Button>
        <Button variant="contained" onClick={handleApprove} disabled={decision.isPending}>
          {decision.isPending ? 'Saving…' : 'Accept documents'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

function BackgroundCheckQueue() {
  const checksQuery = useBackgroundChecks();
  const [selected, setSelected] = useState<BackgroundCheckEntry | null>(null);

  const columns: Column<BackgroundCheckEntry>[] = [
    { key: 'name', header: 'Provider', render: (row) => row.displayName ?? '—' },
    { key: 'skill', header: 'Primary skill', render: (row) => row.primarySkill ?? '—' },
    {
      key: 'started',
      header: 'Check started',
      render: (row) => (row.startedAt ? formatDateTime(row.startedAt) : '—'),
    },
    {
      key: 'status',
      header: 'Status',
      render: (row) =>
        row.status === 'BACKGROUND_CHECK_COMPLETED' ? (
          <Chip size="small" color="info" label="Result recorded" />
        ) : (
          <Chip size="small" color="warning" label="Waiting for result" />
        ),
    },
    {
      key: 'docs',
      header: 'Documents',
      align: 'center',
      render: (row) => <Chip size="small" label={row.documentCount} />,
    },
    {
      key: 'action',
      header: '',
      align: 'right',
      render: () => (
        <Button size="small" variant="outlined">
          Record result
        </Button>
      ),
    },
  ];

  return (
    <>
      <QueryStateView
        isLoading={checksQuery.isLoading}
        isError={checksQuery.isError}
        error={checksQuery.error}
        onRetry={() => void checksQuery.refetch()}
        isEmpty={(checksQuery.data?.length ?? 0) === 0}
        emptyMessage="No providers are waiting on a background check."
      >
        <DataTable
          columns={columns}
          rows={checksQuery.data ?? []}
          rowKey={(row) => row.providerId}
          onRowClick={(row) => setSelected(row)}
        />
      </QueryStateView>

      {selected ? (
        <BackgroundCheckDialog entry={selected} onClose={() => setSelected(null)} />
      ) : null}
    </>
  );
}

interface BackgroundCheckDialogProps {
  entry: BackgroundCheckEntry;
  onClose: () => void;
}

function BackgroundCheckDialog({ entry, onClose }: BackgroundCheckDialogProps) {
  const documentsQuery = useVerificationDocuments(entry.providerId);
  const decision = useBackgroundCheckDecision(entry.providerId);
  const [result, setResult] = useState(entry.result ?? '');
  const [reason, setReason] = useState('');
  const [attempted, setAttempted] = useState(false);
  const [rejecting, setRejecting] = useState(false);

  const resultMissing = !result.trim();
  const reasonMissing = !reason.trim();

  const handleApprove = () => {
    setAttempted(true);
    if (resultMissing) return;
    decision.mutate({ outcome: 'PASSED', result: result.trim() }, { onSuccess: onClose });
  };

  const handleReject = () => {
    setAttempted(true);
    setRejecting(true);
    if (resultMissing || reasonMissing) return;
    decision.mutate(
      { outcome: 'FAILED', result: result.trim(), reason: reason.trim() },
      { onSuccess: onClose },
    );
  };

  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>
        <Stack direction="row" justifyContent="space-between" alignItems="baseline" spacing={2}>
          <span>{entry.displayName ?? 'Provider'} — background check</span>
          {entry.startedAt ? (
            <Typography variant="caption" color="text.secondary">
              Started {formatDateTime(entry.startedAt)}
            </Typography>
          ) : null}
        </Stack>
      </DialogTitle>
      <DialogContent dividers>
        <Alert severity="info" sx={{ mb: 2 }}>
          Run the identity and record check with your verification partner, then record what it
          found. Passing approves the provider for jobs; failing rejects them with the reason you
          give.
        </Alert>

        <QueryStateView
          isLoading={documentsQuery.isLoading}
          isError={documentsQuery.isError}
          error={documentsQuery.error}
          onRetry={() => void documentsQuery.refetch()}
        >
          <DocumentViewer documents={documentsQuery.data ?? []} />
        </QueryStateView>

        <Stack spacing={2} sx={{ mt: 2 }}>
          <TextField
            label="Background check result"
            value={result}
            onChange={(event) => setResult(event.target.value)}
            fullWidth
            multiline
            minRows={2}
            required
            inputProps={{ maxLength: 2000 }}
            error={attempted && resultMissing}
            helperText={
              attempted && resultMissing
                ? 'Record what the check found.'
                : 'For example: "Identity confirmed, no criminal records found." Kept on the provider\'s record.'
            }
          />
          {rejecting ? (
            <TextField
              label="Rejection reason"
              value={reason}
              onChange={(event) => setReason(event.target.value)}
              fullWidth
              multiline
              minRows={2}
              required
              inputProps={{ maxLength: 1000 }}
              error={attempted && reasonMissing}
              helperText={
                attempted && reasonMissing
                  ? 'A reason is required to reject a provider.'
                  : 'Shared with the provider.'
              }
            />
          ) : null}
        </Stack>

        {decision.isError ? (
          <Alert severity="error" sx={{ mt: 2 }}>
            {decision.error.message}
          </Alert>
        ) : null}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={decision.isPending}>
          Cancel
        </Button>
        <Button color="error" onClick={handleReject} disabled={decision.isPending}>
          Failed — reject
        </Button>
        <Button variant="contained" onClick={handleApprove} disabled={decision.isPending}>
          {decision.isPending ? 'Saving…' : 'Passed — approve'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
