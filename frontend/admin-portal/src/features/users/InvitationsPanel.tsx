import { useState } from 'react';
import { Button, Stack, Typography } from '@mui/material';
import { DataTable, type Column } from '@components/DataTable';
import { QueryStateView } from '@components/QueryStateView';
import { ROLE_LABELS } from '@config/roles';
import { ConfirmRemoveDialog } from '@features/tenants/ConfirmRemoveDialog';
import { formatDateTime, shortId } from '@lib/format';
import { useAuthStore } from '@stores/authStore';
import type { StaffInvitation } from './api';
import { useInvitations, useRevokeInvitation } from './hooks';

interface InvitationsPanelProps {
  /** Reports a finished action for the screen's snackbar. */
  onNotice: (message: string) => void;
}

/**
 * The Users module's Invitations tab (email-auth Requirements 6.1, 6.5): open
 * staff invitations with role, inviter and expiry, each revocable. Revoking an
 * ADMIN invitation is a SUPER_ADMIN's call, so the button is not offered to an
 * ADMIN; the service refuses it either way (403 SUPER_ADMIN_REQUIRED).
 */
export function InvitationsPanel({ onNotice }: InvitationsPanelProps) {
  const invitationsQuery = useInvitations();
  const revoke = useRevokeInvitation();
  const isSuperAdmin = useAuthStore((state) => state.user?.roles.includes('SUPER_ADMIN') ?? false);
  const [revoking, setRevoking] = useState<StaffInvitation | null>(null);

  const columns: Column<StaffInvitation>[] = [
    { key: 'email', header: 'Email', render: (row) => row.email },
    { key: 'role', header: 'Role', render: (row) => ROLE_LABELS[row.role] },
    {
      key: 'invitedBy',
      header: 'Invited by',
      render: (row) =>
        row.invitedByName ?? (
          <Typography variant="caption" color="text.secondary">
            {shortId(row.invitedBy)}
          </Typography>
        ),
    },
    { key: 'sent', header: 'Sent', render: (row) => formatDateTime(row.createdAt) },
    { key: 'expires', header: 'Expires', render: (row) => formatDateTime(row.expiresAt) },
    {
      key: 'action',
      header: '',
      align: 'right',
      render: (row) =>
        row.role === 'ADMIN' && !isSuperAdmin ? null : (
          <Button
            size="small"
            color="error"
            variant="outlined"
            onClick={() => {
              revoke.reset();
              setRevoking(row);
            }}
          >
            Revoke
          </Button>
        ),
    },
  ];

  return (
    <Stack spacing={2}>
      <QueryStateView
        isLoading={invitationsQuery.isLoading}
        isError={invitationsQuery.isError}
        error={invitationsQuery.error}
        onRetry={() => void invitationsQuery.refetch()}
        isEmpty={(invitationsQuery.data?.length ?? 0) === 0}
        emptyMessage="No open invitations. Invite staff to send one."
      >
        <DataTable columns={columns} rows={invitationsQuery.data ?? []} rowKey={(row) => row.id} />
      </QueryStateView>

      {revoking ? (
        <ConfirmRemoveDialog
          title="Revoke invitation?"
          message={`The link sent to ${revoking.email} stops working at once. You can invite them again later.`}
          confirmLabel="Revoke"
          pendingLabel="Revoking…"
          isPending={revoke.isPending}
          error={revoke.error}
          onConfirm={() =>
            revoke.mutate(revoking.id, {
              onSuccess: () => {
                setRevoking(null);
                onNotice(`Invitation to ${revoking.email} revoked`);
              },
            })
          }
          onClose={() => setRevoking(null)}
        />
      ) : null}
    </Stack>
  );
}
