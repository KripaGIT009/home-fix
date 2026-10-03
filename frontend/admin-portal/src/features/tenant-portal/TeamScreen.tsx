import { useState } from 'react';
import { Button, Card, CardContent, Collapse, Stack, Typography } from '@mui/material';
import PersonAddAlt1RoundedIcon from '@mui/icons-material/PersonAddAlt1Rounded';
import { ModuleScreen } from '@components/ModuleScreen';
import { QueryStateView } from '@components/QueryStateView';
import { DataTable } from '@components/DataTable';
import { AddByMobileForm } from '@features/tenants/AddByMobileForm';
import { ConfirmRemoveDialog } from '@features/tenants/ConfirmRemoveDialog';
import type { TeamProvider } from '@features/tenants/model';
import { teamProviderColumns } from '@features/tenants/teamColumns';
import { useAddTeamProvider, useRemoveTeamProvider, useTeam } from './hooks';

/**
 * Team (Requirements MT-3, MT-11.4): the agency's Providers with their primary
 * skill, verification status, rating and whether they are available now. The
 * admin adds a Provider by mobile number and removes one; the Requirement MT-3
 * errors (no provider with that number, already in another agency) are shown in
 * plain language. Removing someone leaves jobs already assigned to them alone
 * (Requirement MT-3.3), which the confirmation says.
 */
export function TeamScreen() {
  const teamQuery = useTeam();
  const add = useAddTeamProvider();
  const remove = useRemoveTeamProvider();
  const [adding, setAdding] = useState(false);
  const [removing, setRemoving] = useState<TeamProvider | null>(null);

  const team = teamQuery.data ?? [];
  const columns = teamProviderColumns((provider) => {
    remove.reset();
    setRemoving(provider);
  });

  return (
    <ModuleScreen
      title="Team"
      description="Your agency's providers. Only providers verified by HomeFix can be assigned to requests."
      actions={
        <Button
          variant="contained"
          startIcon={<PersonAddAlt1RoundedIcon />}
          onClick={() => {
            add.reset();
            setAdding((open) => !open);
          }}
        >
          Add provider
        </Button>
      }
    >
      <Collapse in={adding} unmountOnExit>
        <Card sx={{ mb: 2 }}>
          <CardContent>
            <Stack spacing={1.5}>
              <Typography variant="subtitle1">Add a provider by mobile number</Typography>
              <AddByMobileForm
                label="Provider's mobile number"
                submitLabel="Add to team"
                helperText="They must have signed up in the HomeFix provider app with a provider profile."
                onAdd={(mobile) => add.mutateAsync(mobile).then(() => setAdding(false))}
                isPending={add.isPending}
                error={add.error}
              />
            </Stack>
          </CardContent>
        </Card>
      </Collapse>

      <QueryStateView
        isLoading={teamQuery.isLoading}
        isError={teamQuery.isError}
        error={teamQuery.error}
        onRetry={() => void teamQuery.refetch()}
        isEmpty={team.length === 0}
        emptyMessage="No providers in your team yet. Add one by their mobile number."
      >
        <DataTable columns={columns} rows={team} rowKey={(row) => row.providerId} />
      </QueryStateView>

      {removing ? (
        <ConfirmRemoveDialog
          title="Remove from team?"
          message={`${removing.displayName ?? 'This provider'} will no longer be assignable by your agency. Jobs already assigned to them are unaffected.`}
          confirmLabel="Remove"
          isPending={remove.isPending}
          error={remove.error}
          onConfirm={() =>
            remove.mutate(removing.providerId, { onSuccess: () => setRemoving(null) })
          }
          onClose={() => setRemoving(null)}
        />
      ) : null}
    </ModuleScreen>
  );
}
