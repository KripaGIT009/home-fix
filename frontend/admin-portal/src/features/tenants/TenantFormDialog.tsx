import { useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Stack,
  TextField,
  Typography,
  useMediaQuery,
  type Theme,
} from '@mui/material';
import { StatusChip } from '@components/StatusChip';
import type { CategorySummary } from '@features/categories/api';
import { useSaveTenant } from './hooks';
import {
  isApplication,
  TENANT_FIELD_ERRORS,
  tenantErrorMessage,
  type Tenant,
  type TenantStatus,
} from './model';
import { tenantSchema, toTenantPayload, type TenantFormValues } from './schemas';
import { TenantDetailsFields } from './TenantDetailsFields';

interface TenantFormDialogProps {
  /** The Tenant to edit; absent to create one. */
  tenant?: Tenant;
  categories: readonly CategorySummary[];
  onClose: () => void;
  onSaved: (tenant: Tenant) => void;
}

/**
 * Create or edit a Tenant (Requirement MT-12.2) with the shared details fields.
 * Status is set on edit only; a new Tenant starts ACTIVE server-side.
 *
 * An agency application (PENDING_APPROVAL or REJECTED) can have its details
 * corrected here, but not its status: that is decided by approval or rejection,
 * which also make the applicant its administrator and email them (email-auth
 * Requirement 5.4), so the select is replaced by the status it keeps.
 */
export function TenantFormDialog({ tenant, categories, onClose, onSaved }: TenantFormDialogProps) {
  // Phones get the whole screen: a dialog this dense is unusable as a 390 px card.
  const phone = useMediaQuery((theme: Theme) => theme.breakpoints.down('sm'));
  const save = useSaveTenant();
  const [status, setStatus] = useState<TenantStatus>(tenant?.status ?? 'ACTIVE');
  const application = tenant ? isApplication(tenant.status) : false;

  const activeIds = useMemo(
    () => new Set(categories.filter((c) => c.status === 'ACTIVE').map((c) => c.id)),
    [categories],
  );
  const schema = useMemo(() => tenantSchema(activeIds), [activeIds]);

  const form = useForm<TenantFormValues>({
    resolver: zodResolver(schema),
    mode: 'onBlur',
    defaultValues: {
      name: tenant?.name ?? '',
      contactPhone: tenant?.contactPhone ?? '',
      contactEmail: tenant?.contactEmail ?? '',
      baseLatitude: tenant ? String(tenant.baseLatitude) : '',
      baseLongitude: tenant ? String(tenant.baseLongitude) : '',
      serviceRadiusKm: tenant ? String(tenant.serviceRadiusKm) : '10',
      categoryIds: tenant?.categoryIds ?? [],
    },
  });

  const onSubmit = (values: TenantFormValues) => {
    const payload = toTenantPayload(values);
    save.mutate(tenant ? { id: tenant.id, payload: { ...payload, status } } : { payload }, {
      onSuccess: onSaved,
      onError: (error) => {
        const field = TENANT_FIELD_ERRORS[error.code];
        if (field) form.setError(field, { message: error.message });
      },
    });
  };

  const statusField = !tenant ? undefined : application ? (
    <Stack spacing={0.75} sx={{ pt: 0.5 }}>
      <StatusChip status={tenant.status} />
      <Typography variant="caption" color="text.secondary">
        An application&apos;s status changes only by approving or rejecting it.
      </Typography>
    </Stack>
  ) : (
    <TextField
      label="Status"
      select
      fullWidth
      value={status}
      onChange={(event) => setStatus(event.target.value as TenantStatus)}
      helperText={
        status === 'SUSPENDED'
          ? 'Receives no requests; its admins are locked out.'
          : 'Receives requests in its area.'
      }
    >
      <MenuItem value="ACTIVE">Active</MenuItem>
      <MenuItem value="SUSPENDED">Suspended</MenuItem>
    </TextField>
  );

  const fieldError = save.isError && Boolean(TENANT_FIELD_ERRORS[save.error.code]);

  return (
    <Dialog
      open
      onClose={save.isPending ? undefined : onClose}
      maxWidth="md"
      fullWidth
      fullScreen={phone}
    >
      <DialogTitle>{tenant ? `Edit ${tenant.name}` : 'New tenant'}</DialogTitle>
      <form
        noValidate
        onSubmit={(event) => {
          void form.handleSubmit(onSubmit)(event);
        }}
      >
        <DialogContent dividers>
          <TenantDetailsFields form={form} categories={categories} nameAside={statusField} />
          {save.isError && !fieldError ? (
            <Alert severity="error" sx={{ mt: 2 }}>
              {tenantErrorMessage(save.error)}
            </Alert>
          ) : null}
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose} disabled={save.isPending}>
            Cancel
          </Button>
          <Button type="submit" variant="contained" disabled={save.isPending}>
            {save.isPending ? 'Saving…' : tenant ? 'Save changes' : 'Create tenant'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
