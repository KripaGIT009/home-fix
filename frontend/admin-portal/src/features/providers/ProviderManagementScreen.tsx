import { useState } from 'react';
import { Alert, Box, Button, Chip, Rating, Stack, TextField, Typography } from '@mui/material';
import { ModuleScreen } from '@components/ModuleScreen';
import { QueryStateView } from '@components/QueryStateView';
import { DataTable, type Column } from '@components/DataTable';
import { StatusChip } from '@components/StatusChip';
import { formatNumber } from '@lib/format';
import { useProviders, useUpdateProviderStatus } from './hooks';
import type { AdminProvider, ProviderBankAccount } from './api';
import { VerifyBankAccountDialog } from './VerifyBankAccountDialog';

type ProviderWithBankAccount = AdminProvider & { bankAccount: ProviderBankAccount };

/**
 * Provider Management module (Requirement 19.2). Lists service providers with
 * search, shows their verification status, rating and completed-job counts, and
 * lets an admin suspend or reactivate a provider account and mark a provider's
 * settlement bank account verified. Every action is recorded server-side in the
 * Audit_Log (Req 19.8).
 */
export function ProviderManagementScreen() {
  const [searchInput, setSearchInput] = useState('');
  const [search, setSearch] = useState('');
  const providersQuery = useProviders(search);
  const updateStatus = useUpdateProviderStatus();
  const [verifying, setVerifying] = useState<ProviderWithBankAccount | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const columns: Column<AdminProvider>[] = [
    { key: 'name', header: 'Provider', render: (row) => row.displayName },
    { key: 'mobile', header: 'Mobile', render: (row) => row.mobileNumber ?? '—' },
    { key: 'skill', header: 'Primary skill', render: (row) => row.primarySkill ?? '—' },
    {
      key: 'verification',
      header: 'Verification',
      render: (row) => <StatusChip status={row.verificationStatus} />,
    },
    {
      key: 'rating',
      header: 'Rating',
      render: (row) =>
        row.rating == null ? (
          '—'
        ) : (
          <Stack direction="row" spacing={0.5} alignItems="center">
            <Rating value={row.rating} precision={0.1} size="small" readOnly />
            <Typography variant="caption" color="text.secondary">
              {row.rating.toFixed(1)}
            </Typography>
          </Stack>
        ),
    },
    {
      key: 'jobs',
      header: 'Jobs',
      align: 'right',
      render: (row) => (row.completedJobs == null ? '—' : formatNumber(row.completedJobs)),
    },
    {
      key: 'online',
      header: 'Availability',
      render: (row) =>
        row.isOnline == null ? '—' : <StatusChip status={row.isOnline ? 'ONLINE' : 'INACTIVE'} />,
    },
    { key: 'status', header: 'Status', render: (row) => <StatusChip status={row.status} /> },
    {
      key: 'bank',
      header: 'Bank account',
      render: (row) =>
        row.bankAccount ? (
          <Stack spacing={0.5} alignItems="flex-start">
            <Typography variant="body2" sx={{ fontVariantNumeric: 'tabular-nums' }} noWrap>
              {row.bankAccount.masked}
            </Typography>
            <Chip
              size="small"
              color={row.bankAccount.verified ? 'success' : 'warning'}
              label={row.bankAccount.verified ? 'Verified' : 'Pending verification'}
            />
          </Stack>
        ) : (
          <Typography variant="body2" color="text.secondary">
            None
          </Typography>
        ),
    },
    {
      key: 'action',
      header: '',
      align: 'right',
      render: (row) => {
        const { bankAccount } = row;
        const canVerifyBank = bankAccount != null && !bankAccount.verified;
        // Unknown status (verification service unreachable): offer no toggle
        // rather than guess which way it should go.
        const suspend = row.status === 'ACTIVE';
        if (!row.status && !canVerifyBank) return null;
        return (
          <Stack spacing={1} alignItems="flex-end">
            {row.status ? (
              <Button
                size="small"
                color={suspend ? 'error' : 'success'}
                variant="outlined"
                disabled={updateStatus.isPending}
                onClick={() =>
                  updateStatus.mutate({ id: row.id, status: suspend ? 'SUSPENDED' : 'ACTIVE' })
                }
              >
                {suspend ? 'Suspend' : 'Reactivate'}
              </Button>
            ) : null}
            {canVerifyBank ? (
              <Button
                size="small"
                variant="outlined"
                sx={{ whiteSpace: 'nowrap' }}
                onClick={() => {
                  setNotice(null);
                  setVerifying({ ...row, bankAccount });
                }}
              >
                Mark bank account verified
              </Button>
            ) : null}
          </Stack>
        );
      },
    },
  ];

  return (
    <ModuleScreen
      title="Provider Management"
      description="Search, review, and manage service provider accounts."
    >
      <Stack
        component="form"
        direction="row"
        spacing={1}
        sx={{ mb: 2 }}
        onSubmit={(event) => {
          event.preventDefault();
          setSearch(searchInput.trim());
        }}
      >
        <TextField
          size="small"
          label="Search by name, mobile, or skill"
          value={searchInput}
          onChange={(event) => setSearchInput(event.target.value)}
          sx={{ maxWidth: 360, flexGrow: 1 }}
        />
        <Box>
          <Button type="submit" variant="contained">
            Search
          </Button>
        </Box>
      </Stack>

      {notice ? (
        <Alert severity="success" sx={{ mb: 2 }} onClose={() => setNotice(null)}>
          {notice}
        </Alert>
      ) : null}

      {updateStatus.isError ? (
        <Alert severity="error" sx={{ mb: 2 }}>
          {updateStatus.error.message}
        </Alert>
      ) : null}

      <QueryStateView
        isLoading={providersQuery.isLoading}
        isError={providersQuery.isError}
        error={providersQuery.error}
        onRetry={() => void providersQuery.refetch()}
        isEmpty={(providersQuery.data?.length ?? 0) === 0}
        emptyMessage="No providers match your search."
      >
        <DataTable columns={columns} rows={providersQuery.data ?? []} rowKey={(row) => row.id} />
      </QueryStateView>

      {verifying ? (
        <VerifyBankAccountDialog
          provider={verifying}
          onClose={() => setVerifying(null)}
          onVerified={(name) => {
            setVerifying(null);
            setNotice(`${name}'s bank account is now verified. Settlements can be paid into it.`);
          }}
        />
      ) : null}
    </ModuleScreen>
  );
}
