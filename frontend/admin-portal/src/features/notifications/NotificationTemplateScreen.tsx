import { useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  TextField,
} from '@mui/material';
import { ModuleScreen } from '@components/ModuleScreen';
import { QueryStateView } from '@components/QueryStateView';
import { DataTable, type Column } from '@components/DataTable';
import { useTemplates, useUpdateTemplate } from './hooks';
import type { NotificationTemplate } from './api';

/**
 * Notification Templates module (Requirement 19.2). Lists the message templates
 * the Notification Service renders per channel, and lets an admin edit a
 * template's subject (email) and body.
 */
export function NotificationTemplateScreen() {
  const templatesQuery = useTemplates();
  const [editing, setEditing] = useState<NotificationTemplate | null>(null);

  const columns: Column<NotificationTemplate>[] = [
    { key: 'name', header: 'Template', render: (row) => row.name },
    { key: 'key', header: 'Key', render: (row) => row.key },
    {
      key: 'channel',
      header: 'Channel',
      render: (row) => <Chip size="small" label={row.channel.replace('_', ' ')} />,
    },
    { key: 'subject', header: 'Subject', render: (row) => row.subject ?? '—' },
    {
      key: 'action',
      header: '',
      align: 'right',
      render: (row) => (
        <Button size="small" variant="outlined" onClick={() => setEditing(row)}>
          Edit
        </Button>
      ),
    },
  ];

  return (
    <ModuleScreen
      title="Notification Templates"
      description="Edit the messages sent across push, SMS, email, and in-app channels."
    >
      <QueryStateView
        isLoading={templatesQuery.isLoading}
        isError={templatesQuery.isError}
        error={templatesQuery.error}
        onRetry={() => void templatesQuery.refetch()}
        isEmpty={(templatesQuery.data?.length ?? 0) === 0}
        emptyMessage="No notification templates configured."
      >
        <DataTable columns={columns} rows={templatesQuery.data ?? []} rowKey={(row) => row.id} />
      </QueryStateView>

      {editing ? <EditDialog template={editing} onClose={() => setEditing(null)} /> : null}
    </ModuleScreen>
  );
}

interface EditDialogProps {
  template: NotificationTemplate;
  onClose: () => void;
}

function EditDialog({ template, onClose }: EditDialogProps) {
  const update = useUpdateTemplate();
  const isEmail = template.channel === 'EMAIL';
  const [subject, setSubject] = useState(template.subject ?? '');
  const [body, setBody] = useState(template.body);

  useEffect(() => {
    setSubject(template.subject ?? '');
    setBody(template.body);
  }, [template]);

  const bodyInvalid = !body.trim();
  const [touched, setTouched] = useState(false);

  const handleSave = () => {
    setTouched(true);
    if (bodyInvalid) return;
    const payload = isEmail && subject.trim() ? { subject: subject.trim(), body } : { body };
    update.mutate({ id: template.id, payload }, { onSuccess: onClose });
  };

  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>{template.name}</DialogTitle>
      <DialogContent dividers>
        <Stack spacing={2}>
          {isEmail ? (
            <TextField
              label="Subject"
              value={subject}
              onChange={(event) => setSubject(event.target.value)}
              fullWidth
            />
          ) : null}
          <TextField
            label="Body"
            value={body}
            onChange={(event) => setBody(event.target.value)}
            fullWidth
            multiline
            minRows={5}
            required
            error={touched && bodyInvalid}
            helperText={
              touched && bodyInvalid
                ? 'The template body cannot be empty.'
                : 'Use {{placeholders}} for dynamic values.'
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
