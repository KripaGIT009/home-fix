import { useState } from 'react';
import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Rating,
  Stack,
  TextField,
  Tooltip,
} from '@mui/material';
import FlagRoundedIcon from '@mui/icons-material/FlagRounded';
import { ModuleScreen } from '@components/ModuleScreen';
import { QueryStateView } from '@components/QueryStateView';
import { DataTable, type Column } from '@components/DataTable';
import { StatusChip } from '@components/StatusChip';
import { formatDateTime } from '@lib/format';
import { useModerateReview, useReviews } from './hooks';
import type { AdminReview, ModerationStatus } from './api';

const STATUS_OPTIONS: ReadonlyArray<ModerationStatus> = [
  'PENDING',
  'FLAGGED',
  'PUBLISHED',
  'REMOVED',
];

/**
 * Review Moderation module (Requirement 19.2). Lists reviews with a moderation
 * status filter — defaulting to auto-flagged ones — and lets an admin publish
 * or remove a review. Removals require a reason and trigger a server-side
 * rating recalculation.
 */
export function ReviewModerationScreen() {
  const [status, setStatus] = useState<ModerationStatus | ''>('FLAGGED');
  const [removing, setRemoving] = useState<AdminReview | null>(null);

  const reviewsQuery = useReviews(status);
  const moderate = useModerateReview();

  const columns: Column<AdminReview>[] = [
    { key: 'provider', header: 'Provider', render: (row) => row.providerName },
    { key: 'reviewer', header: 'Reviewer', render: (row) => row.reviewerName },
    {
      key: 'rating',
      header: 'Rating',
      render: (row) => <Rating value={row.rating} precision={0.5} size="small" readOnly />,
    },
    { key: 'comment', header: 'Comment', render: (row) => row.comment },
    {
      key: 'status',
      header: 'Status',
      render: (row) => (
        <Stack direction="row" spacing={0.5} alignItems="center">
          <StatusChip status={row.status} />
          {row.flagReason ? (
            <Tooltip title={row.flagReason}>
              <FlagRoundedIcon color="warning" fontSize="small" />
            </Tooltip>
          ) : null}
        </Stack>
      ),
    },
    { key: 'created', header: 'Date', render: (row) => formatDateTime(row.createdAt) },
    {
      key: 'action',
      header: '',
      align: 'right',
      render: (row) => {
        const canModerate = row.status === 'PENDING' || row.status === 'FLAGGED';
        if (!canModerate) return null;
        return (
          <Stack direction="row" spacing={1} justifyContent="flex-end">
            <Button
              size="small"
              variant="outlined"
              color="success"
              disabled={moderate.isPending}
              onClick={() => moderate.mutate({ id: row.id, payload: { action: 'PUBLISH' } })}
            >
              Publish
            </Button>
            <Button
              size="small"
              variant="outlined"
              color="error"
              disabled={moderate.isPending}
              onClick={() => setRemoving(row)}
            >
              Remove
            </Button>
          </Stack>
        );
      },
    },
  ];

  return (
    <ModuleScreen
      title="Review Moderation"
      description="Publish or remove reviews. Flagged reviews are shown first."
    >
      <Stack direction="row" spacing={1} sx={{ mb: 2 }} alignItems="center">
        <TextField
          size="small"
          select
          label="Moderation status"
          value={status}
          onChange={(event) => setStatus(event.target.value as ModerationStatus | '')}
          sx={{ minWidth: 200 }}
        >
          <MenuItem value="">All statuses</MenuItem>
          {STATUS_OPTIONS.map((option) => (
            <MenuItem key={option} value={option}>
              {option.toLowerCase()}
            </MenuItem>
          ))}
        </TextField>
      </Stack>

      {moderate.isError ? (
        <Alert severity="error" sx={{ mb: 2 }}>
          {moderate.error.message}
        </Alert>
      ) : null}

      <QueryStateView
        isLoading={reviewsQuery.isLoading}
        isError={reviewsQuery.isError}
        error={reviewsQuery.error}
        onRetry={() => void reviewsQuery.refetch()}
        isEmpty={(reviewsQuery.data?.length ?? 0) === 0}
        emptyMessage="No reviews match this filter."
      >
        <DataTable columns={columns} rows={reviewsQuery.data ?? []} rowKey={(row) => row.id} />
      </QueryStateView>

      {removing ? <RemoveDialog review={removing} onClose={() => setRemoving(null)} /> : null}
    </ModuleScreen>
  );
}

interface RemoveDialogProps {
  review: AdminReview;
  onClose: () => void;
}

function RemoveDialog({ review, onClose }: RemoveDialogProps) {
  const moderate = useModerateReview();
  const [reason, setReason] = useState('');
  const [touched, setTouched] = useState(false);

  const handleConfirm = () => {
    setTouched(true);
    if (!reason.trim()) return;
    moderate.mutate(
      { id: review.id, payload: { action: 'REMOVE', reason: reason.trim() } },
      { onSuccess: onClose },
    );
  };

  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Remove review by {review.reviewerName}</DialogTitle>
      <DialogContent dividers>
        <TextField
          label="Removal reason"
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          fullWidth
          multiline
          minRows={2}
          required
          error={touched && !reason.trim()}
          helperText={
            touched && !reason.trim() ? 'A reason is required to remove a review.' : undefined
          }
        />
        {moderate.isError ? (
          <Alert severity="error" sx={{ mt: 2 }}>
            {moderate.error.message}
          </Alert>
        ) : null}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={moderate.isPending}>
          Cancel
        </Button>
        <Button
          color="error"
          variant="contained"
          onClick={handleConfirm}
          disabled={moderate.isPending}
        >
          {moderate.isPending ? 'Removing…' : 'Remove'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
