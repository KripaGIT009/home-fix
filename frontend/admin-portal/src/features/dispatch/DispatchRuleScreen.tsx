import { useEffect, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Divider,
  Grid,
  InputAdornment,
  Snackbar,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { ModuleScreen } from '@components/ModuleScreen';
import { QueryStateView } from '@components/QueryStateView';
import { DISPATCH_EDIT_ROLES, hasAnyRole } from '@config/roles';
import { useAuthStore } from '@stores/authStore';
import { useDispatchConfig, useUpdateDispatchConfig } from './hooks';
import { validateWeights, WEIGHT_FIELDS } from './weights';
import type { DispatchConfig, DispatchWeights } from './api';

/** Round a weight to 2 decimals for a clean server-side sum. */
function round2(value: number): number {
  return Math.round(value * 100) / 100;
}

/**
 * Dispatch Rule Configuration module (Requirement 19.5 / 8.4).
 *
 * Lets an admin tune the five provider-matching weights and the radius/timeout
 * parameters. The Save action is disabled until the weights are valid: each in
 * 0.0–1.0 and summing to exactly 1.0. A live sum indicator shows the running
 * total so the admin can see how close they are to 1.0, and inline errors name
 * the failing condition — matching the server-side rejection contract.
 *
 * Admins and dispatchers may review the configuration, but only a SUPER_ADMIN
 * may save it: the weights steer every job offer on the platform, so the
 * Dispatch Engine treats them as System Configuration and refuses the PUT from
 * anyone else. For other roles the form is read-only and says why.
 */
export function DispatchRuleScreen() {
  const configQuery = useDispatchConfig();

  return (
    <ModuleScreen
      title="Dispatch Rules"
      description="Provider matching weights and search radius configuration."
    >
      <QueryStateView
        isLoading={configQuery.isLoading}
        isError={configQuery.isError}
        error={configQuery.error}
        onRetry={() => void configQuery.refetch()}
      >
        {configQuery.data ? <DispatchForm initial={configQuery.data} /> : null}
      </QueryStateView>
    </ModuleScreen>
  );
}

function DispatchForm({ initial }: { initial: DispatchConfig }) {
  const update = useUpdateDispatchConfig();
  const canEdit = useAuthStore((state) => hasAnyRole(state.user?.roles ?? [], DISPATCH_EDIT_ROLES));
  const [weights, setWeights] = useState<DispatchWeights>(initial.weights);
  const [initialRadiusKm, setInitialRadiusKm] = useState(initial.initialRadiusKm);
  const [radiusIncrementKm, setRadiusIncrementKm] = useState(initial.radiusIncrementKm);
  const [maxExpansionCycles, setMaxExpansionCycles] = useState(initial.maxExpansionCycles);
  const [offerTimeoutSeconds, setOfferTimeoutSeconds] = useState(initial.offerTimeoutSeconds);
  const [saved, setSaved] = useState(false);

  // Keep local state in sync if the query refetches new server values.
  useEffect(() => {
    setWeights(initial.weights);
    setInitialRadiusKm(initial.initialRadiusKm);
    setRadiusIncrementKm(initial.radiusIncrementKm);
    setMaxExpansionCycles(initial.maxExpansionCycles);
    setOfferTimeoutSeconds(initial.offerTimeoutSeconds);
  }, [initial]);

  const validation = validateWeights(weights);

  const handleWeightChange = (key: keyof DispatchWeights, raw: string) => {
    const value = raw === '' ? 0 : Number(raw);
    setWeights((prev) => ({ ...prev, [key]: value }));
  };

  const handleSubmit = () => {
    if (!canEdit || !validation.isValid) return;
    const rounded = WEIGHT_FIELDS.reduce(
      (acc, field) => ({ ...acc, [field.key]: round2(weights[field.key]) }),
      {} as DispatchWeights,
    );
    update.mutate(
      {
        weights: rounded,
        initialRadiusKm,
        radiusIncrementKm,
        maxExpansionCycles,
        offerTimeoutSeconds,
      },
      { onSuccess: () => setSaved(true) },
    );
  };

  return (
    <Stack spacing={3}>
      {canEdit ? null : (
        <Alert severity="info">
          Read-only view. Only a Super Admin can change dispatch rules: the weights and search
          radius steer every job offer on the platform, so they are treated as system configuration.
        </Alert>
      )}

      <Card variant="outlined">
        <CardContent>
          <Typography variant="h6" gutterBottom>
            Matching weights
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            Each weight must be between 0.0 and 1.0, and all five must sum to exactly 1.0.
          </Typography>
          <Grid container spacing={2}>
            {WEIGHT_FIELDS.map((field) => {
              const invalid = validation.outOfRange.includes(field.key);
              return (
                <Grid item xs={12} sm={6} md={4} key={field.key}>
                  <TextField
                    label={field.label}
                    type="number"
                    fullWidth
                    value={weights[field.key]}
                    onChange={(event) => handleWeightChange(field.key, event.target.value)}
                    error={invalid}
                    inputProps={{ min: 0, max: 1, step: 0.05, readOnly: !canEdit }}
                  />
                </Grid>
              );
            })}
          </Grid>

          <Divider sx={{ my: 2 }} />

          <Stack direction="row" alignItems="center" spacing={2}>
            <Typography variant="subtitle1" fontWeight={700}>
              Sum: {validation.sum.toFixed(2)}
            </Typography>
            {validation.isValid ? (
              <Alert severity="success" sx={{ py: 0 }}>
                Weights are valid.
              </Alert>
            ) : (
              <Alert severity="warning" sx={{ py: 0 }}>
                {validation.message}
              </Alert>
            )}
          </Stack>
        </CardContent>
      </Card>

      <Card variant="outlined">
        <CardContent>
          <Typography variant="h6" gutterBottom>
            Search radius &amp; timeouts
          </Typography>
          <Grid container spacing={2}>
            <Grid item xs={12} sm={6} md={3}>
              <TextField
                label="Initial radius"
                type="number"
                fullWidth
                value={initialRadiusKm}
                onChange={(event) => setInitialRadiusKm(Number(event.target.value))}
                InputProps={{ endAdornment: <InputAdornment position="end">km</InputAdornment> }}
                inputProps={{ min: 1, readOnly: !canEdit }}
              />
            </Grid>
            <Grid item xs={12} sm={6} md={3}>
              <TextField
                label="Radius increment"
                type="number"
                fullWidth
                value={radiusIncrementKm}
                onChange={(event) => setRadiusIncrementKm(Number(event.target.value))}
                InputProps={{ endAdornment: <InputAdornment position="end">km</InputAdornment> }}
                inputProps={{ min: 1, readOnly: !canEdit }}
              />
            </Grid>
            <Grid item xs={12} sm={6} md={3}>
              <TextField
                label="Max expansion cycles"
                type="number"
                fullWidth
                value={maxExpansionCycles}
                onChange={(event) => setMaxExpansionCycles(Number(event.target.value))}
                inputProps={{ min: 0, max: 10, readOnly: !canEdit }}
              />
            </Grid>
            <Grid item xs={12} sm={6} md={3}>
              <TextField
                label="Offer timeout"
                type="number"
                fullWidth
                value={offerTimeoutSeconds}
                onChange={(event) => setOfferTimeoutSeconds(Number(event.target.value))}
                InputProps={{ endAdornment: <InputAdornment position="end">s</InputAdornment> }}
                inputProps={{ min: 5, readOnly: !canEdit }}
              />
            </Grid>
          </Grid>
        </CardContent>
      </Card>

      {update.isError ? <Alert severity="error">{update.error.message}</Alert> : null}

      <Box>
        <Button
          variant="contained"
          size="large"
          onClick={handleSubmit}
          disabled={!canEdit || !validation.isValid || update.isPending}
        >
          {update.isPending ? 'Saving…' : 'Save configuration'}
        </Button>
        {canEdit ? null : (
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
            Saving is restricted to Super Admins.
          </Typography>
        )}
      </Box>

      <Snackbar
        open={saved}
        autoHideDuration={4000}
        onClose={() => setSaved(false)}
        message="Dispatch configuration saved"
      />
    </Stack>
  );
}
