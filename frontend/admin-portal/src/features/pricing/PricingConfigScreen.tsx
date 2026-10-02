import { useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Grid,
  Snackbar,
  TextField,
} from '@mui/material';
import { ModuleScreen } from '@components/ModuleScreen';
import { QueryStateView } from '@components/QueryStateView';
import { DataTable, type Column } from '@components/DataTable';
import { formatCurrency } from '@lib/format';
import { usePricingConfigs, useUpdatePricingConfig } from './hooks';
import { subcategoryLabel, type PricingConfig, type PricingConfigUpdate } from './api';

/**
 * Pricing Configuration module (Requirement 6.11). Lists the per-subcategory
 * pricing parameters and lets an admin edit them in a dialog. Saved changes are
 * applied by the Pricing Engine to new bookings within 60 seconds without a
 * service restart.
 */
export function PricingConfigScreen() {
  const configsQuery = usePricingConfigs();
  const [editing, setEditing] = useState<PricingConfig | null>(null);
  const [saved, setSaved] = useState(false);

  const columns: Column<PricingConfig>[] = [
    { key: 'category', header: 'Category', render: (row) => row.categoryName ?? '—' },
    { key: 'subcategory', header: 'Subcategory', render: (row) => subcategoryLabel(row) },
    {
      key: 'base',
      header: 'Base price',
      align: 'right',
      render: (row) => formatCurrency(row.basePrice, row.currency ?? undefined),
    },
    {
      key: 'perKm',
      header: 'Per km',
      align: 'right',
      render: (row) => formatCurrency(row.perKmRate, row.currency ?? undefined),
    },
    {
      key: 'platformFee',
      header: 'Platform fee',
      align: 'right',
      render: (row) => `${row.platformFeePercent}%`,
    },
    {
      key: 'action',
      header: '',
      align: 'right',
      render: () => (
        <Button size="small" variant="outlined">
          Edit
        </Button>
      ),
    },
  ];

  return (
    <ModuleScreen
      title="Pricing Configuration"
      description="Per-subcategory pricing parameters. Changes apply to new bookings within 60s."
    >
      <QueryStateView
        isLoading={configsQuery.isLoading}
        isError={configsQuery.isError}
        error={configsQuery.error}
        onRetry={() => void configsQuery.refetch()}
        isEmpty={(configsQuery.data?.length ?? 0) === 0}
        emptyMessage="No pricing configurations found."
      >
        <DataTable
          columns={columns}
          rows={configsQuery.data ?? []}
          rowKey={(row) => row.subcategoryId}
          onRowClick={(row) => setEditing(row)}
        />
      </QueryStateView>

      {editing ? (
        <PricingDialog
          config={editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            setSaved(true);
          }}
        />
      ) : null}

      <Snackbar
        open={saved}
        autoHideDuration={4000}
        onClose={() => setSaved(false)}
        message="Pricing configuration saved"
      />
    </ModuleScreen>
  );
}

interface PricingDialogProps {
  config: PricingConfig;
  onClose: () => void;
  onSaved: () => void;
}

/** Numeric fields exposed for editing, with labels. */
const NUMERIC_FIELDS: ReadonlyArray<{ key: keyof PricingConfigUpdate; label: string }> = [
  { key: 'basePrice', label: 'Base price' },
  { key: 'perKmRate', label: 'Per-km rate' },
  { key: 'maxTravelCharge', label: 'Max travel charge' },
  { key: 'platformFeePercent', label: 'Platform fee (%)' },
  { key: 'nightSurcharge', label: 'Night surcharge' },
  { key: 'weekendSurcharge', label: 'Weekend surcharge' },
  { key: 'emergencyMultiplierCap', label: 'Emergency multiplier cap' },
  { key: 'surgeMultiplierCap', label: 'Surge multiplier cap' },
];

/** The editable slice of a config, which is all the PUT sends. */
function editableFields(config: PricingConfig): PricingConfigUpdate {
  return {
    basePrice: config.basePrice,
    perKmRate: config.perKmRate,
    maxTravelCharge: config.maxTravelCharge,
    platformFeePercent: config.platformFeePercent,
    nightSurcharge: config.nightSurcharge,
    weekendSurcharge: config.weekendSurcharge,
    emergencyMultiplierCap: config.emergencyMultiplierCap,
    surgeMultiplierCap: config.surgeMultiplierCap,
  };
}

function PricingDialog({ config, onClose, onSaved }: PricingDialogProps) {
  const update = useUpdatePricingConfig();
  const [draft, setDraft] = useState<PricingConfigUpdate>(() => editableFields(config));

  useEffect(() => setDraft(editableFields(config)), [config]);

  const handleChange = (key: keyof PricingConfigUpdate, raw: string) => {
    setDraft((prev) => ({ ...prev, [key]: raw === '' ? 0 : Number(raw) }));
  };

  const handleSave = () => {
    update.mutate({ subcategoryId: config.subcategoryId, update: draft }, { onSuccess: onSaved });
  };

  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>
        {config.categoryName ? `${config.categoryName} — ` : ''}
        {subcategoryLabel(config)}
      </DialogTitle>
      <DialogContent dividers>
        <Grid container spacing={2} sx={{ mt: 0 }}>
          {NUMERIC_FIELDS.map((field) => (
            <Grid item xs={12} sm={6} key={field.key}>
              <TextField
                label={field.label}
                type="number"
                fullWidth
                value={draft[field.key] ?? ''}
                onChange={(event) => handleChange(field.key, event.target.value)}
                inputProps={{ min: 0, step: 0.01 }}
              />
            </Grid>
          ))}
        </Grid>
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
