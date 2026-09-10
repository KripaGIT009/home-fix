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
  TextField,
  Typography,
} from '@mui/material';
import { ModuleScreen } from '@components/ModuleScreen';
import { QueryStateView } from '@components/QueryStateView';
import { DataTable, type Column } from '@components/DataTable';
import { formatDateTime } from '@lib/format';
import { DocumentViewer } from './DocumentViewer';
import { useVerificationDecision, useVerificationDocuments, useVerificationQueue } from './hooks';
import type { VerificationQueueEntry } from './api';

/**
 * Verification Queue module (Requirement 19.3).
 *
 * Lists all providers in DOCUMENT_SUBMITTED status sorted oldest-first, and
 * opens a review dialog where each submitted document is rendered inline
 * (PDFs in an embedded frame) without a separate download. An admin can
 * approve or reject the submission from the same dialog.
 */
export function VerificationQueueScreen() {
  const queueQuery = useVerificationQueue();
  const [selected, setSelected] = useState<VerificationQueueEntry | null>(null);

  const columns: Column<VerificationQueueEntry>[] = [
    { key: 'name', header: 'Provider', render: (row) => row.displayName },
    { key: 'mobile', header: 'Mobile', render: (row) => row.mobileNumber },
    { key: 'skill', header: 'Primary skill', render: (row) => row.primarySkill },
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
    <ModuleScreen
      title="Verification Queue"
      description="Providers awaiting document review, sorted oldest first."
    >
      <QueryStateView
        isLoading={queueQuery.isLoading}
        isError={queueQuery.isError}
        error={queueQuery.error}
        onRetry={() => void queueQuery.refetch()}
        isEmpty={(queueQuery.data?.length ?? 0) === 0}
        emptyMessage="No providers are waiting for verification."
      >
        <DataTable
          columns={columns}
          rows={queueQuery.data ?? []}
          rowKey={(row) => row.providerId}
          onRowClick={(row) => setSelected(row)}
        />
      </QueryStateView>

      {selected ? <ReviewDialog entry={selected} onClose={() => setSelected(null)} /> : null}
    </ModuleScreen>
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
          <span>{entry.displayName}</span>
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
          {decision.isPending ? 'Saving…' : 'Approve'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
