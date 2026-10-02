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
  MenuItem,
  Stack,
  TextField,
} from '@mui/material';
import { ModuleScreen } from '@components/ModuleScreen';
import { QueryStateView } from '@components/QueryStateView';
import { DataTable, type Column } from '@components/DataTable';
import { StatusChip } from '@components/StatusChip';
import { formatDateTime } from '@lib/format';
import { useComplaints, useUpdateComplaint } from './hooks';
import {
  COMPLAINT_STATUSES,
  complaintTransitions,
  isSlaBreached,
  isTerminalComplaint,
  type AdminComplaint,
  type ComplaintStatus,
} from './api';

/** "REFUND_FAILED" -> "refund failed", matching the StatusChip label. */
function statusLabel(status: ComplaintStatus): string {
  return status.replace(/_/g, ' ').toLowerCase();
}

/**
 * Complaint Management module (Requirement 19.2). Lists complaints with search
 * and a status filter, flags SLA breaches, and lets an agent update the status
 * and record a resolution note.
 */
export function ComplaintManagementScreen() {
  const [searchInput, setSearchInput] = useState('');
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState<ComplaintStatus | ''>('');
  const [editing, setEditing] = useState<AdminComplaint | null>(null);

  const complaintsQuery = useComplaints(search, status);

  const columns: Column<AdminComplaint>[] = [
    { key: 'booking', header: 'Booking', render: (row) => row.bookingReference ?? '—' },
    { key: 'raisedBy', header: 'Raised by', render: (row) => row.raisedByName ?? '—' },
    { key: 'category', header: 'Category', render: (row) => row.category },
    { key: 'summary', header: 'Summary', render: (row) => row.summary },
    { key: 'status', header: 'Status', render: (row) => <StatusChip status={row.status} /> },
    {
      key: 'sla',
      header: 'SLA',
      render: (row) =>
        isSlaBreached(row) ? (
          <Chip size="small" color="error" label="Breached" />
        ) : (
          formatDateTime(row.slaDueAt)
        ),
    },
    {
      key: 'action',
      header: '',
      align: 'right',
      render: (row) => (
        <Button size="small" variant="outlined" onClick={() => setEditing(row)}>
          Manage
        </Button>
      ),
    },
  ];

  return (
    <ModuleScreen
      title="Complaints"
      description="Triage and resolve complaints within their SLA windows."
    >
      <Stack
        component="form"
        direction={{ xs: 'column', sm: 'row' }}
        spacing={1}
        sx={{ mb: 2 }}
        onSubmit={(event) => {
          event.preventDefault();
          setSearch(searchInput.trim());
        }}
      >
        <TextField
          size="small"
          label="Search by booking or complainant"
          value={searchInput}
          onChange={(event) => setSearchInput(event.target.value)}
          sx={{ maxWidth: 360, flexGrow: 1 }}
        />
        <TextField
          size="small"
          select
          label="Status"
          value={status}
          onChange={(event) => setStatus(event.target.value as ComplaintStatus | '')}
          sx={{ minWidth: 180 }}
        >
          <MenuItem value="">All statuses</MenuItem>
          {COMPLAINT_STATUSES.map((option) => (
            <MenuItem key={option} value={option}>
              {statusLabel(option)}
            </MenuItem>
          ))}
        </TextField>
        <Box>
          <Button type="submit" variant="contained">
            Search
          </Button>
        </Box>
      </Stack>

      <QueryStateView
        isLoading={complaintsQuery.isLoading}
        isError={complaintsQuery.isError}
        error={complaintsQuery.error}
        onRetry={() => void complaintsQuery.refetch()}
        isEmpty={(complaintsQuery.data?.length ?? 0) === 0}
        emptyMessage="No complaints match your filters."
      >
        <DataTable columns={columns} rows={complaintsQuery.data ?? []} rowKey={(row) => row.id} />
      </QueryStateView>

      {editing ? <ManageDialog complaint={editing} onClose={() => setEditing(null)} /> : null}
    </ModuleScreen>
  );
}

interface ManageDialogProps {
  complaint: AdminComplaint;
  onClose: () => void;
}

function ManageDialog({ complaint, onClose }: ManageDialogProps) {
  const update = useUpdateComplaint();
  const [nextStatus, setNextStatus] = useState<ComplaintStatus>(complaint.status);
  const [note, setNote] = useState('');

  // The current status stays selectable so a note can be added without moving
  // the complaint; the rest are only the moves the Complaint Service accepts.
  const terminal = isTerminalComplaint(complaint.status);
  const statusOptions = [complaint.status, ...complaintTransitions(complaint.status)];

  const noteRequired = nextStatus === 'RESOLVED';
  const noteInvalid = noteRequired && !note.trim();
  const [touched, setTouched] = useState(false);

  const handleSave = () => {
    setTouched(true);
    if (noteInvalid) return;
    const payload = note.trim()
      ? { status: nextStatus, resolutionNote: note.trim() }
      : { status: nextStatus };
    update.mutate({ id: complaint.id, payload }, { onSuccess: onClose });
  };

  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>
        {complaint.bookingReference ? `Complaint — ${complaint.bookingReference}` : 'Complaint'}
      </DialogTitle>
      <DialogContent dividers>
        <Stack spacing={2}>
          <TextField
            select
            label="Status"
            value={nextStatus}
            onChange={(event) => setNextStatus(event.target.value as ComplaintStatus)}
            fullWidth
            disabled={terminal}
            helperText={
              terminal
                ? `This complaint is ${statusLabel(complaint.status)} and can't be reopened; you can still add a note.`
                : undefined
            }
          >
            {statusOptions.map((option) => (
              <MenuItem key={option} value={option}>
                {option === complaint.status
                  ? `${statusLabel(option)} (current)`
                  : statusLabel(option)}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            label="Resolution note"
            value={note}
            onChange={(event) => setNote(event.target.value)}
            multiline
            minRows={3}
            fullWidth
            error={touched && noteInvalid}
            helperText={
              touched && noteInvalid
                ? 'A resolution note is required when resolving a complaint.'
                : 'Optional unless resolving.'
            }
          />
        </Stack>
        {update.isError ? (
          <Alert severity="error" sx={{ mt: 2 }}>
            {update.error.message}
          </Alert>
        ) : null}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={update.isPending}>
          Cancel
        </Button>
        <Button variant="contained" onClick={handleSave} disabled={update.isPending}>
          {update.isPending ? 'Saving…' : 'Save'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
