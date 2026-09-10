import { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  FormControlLabel,
  Snackbar,
  Stack,
  Switch,
  TextField,
  Typography,
} from '@mui/material';
import { ModuleScreen } from '@components/ModuleScreen';
import { ForbiddenScreen } from '@components/ForbiddenScreen';
import { QueryStateView } from '@components/QueryStateView';
import { useAuthStore } from '@stores/authStore';
import { useSystemConfig, useUpdateSystemConfig } from './hooks';
import type { SystemSetting } from './api';

/**
 * System Configuration module (Requirement 19.2, Requirement 19.6/19.7).
 *
 * SUPER_ADMIN only. Although the sidebar hides this module for ADMIN and the
 * route guard blocks navigation, this screen independently re-checks the role
 * so a direct render (e.g. via a stale link) still shows the Forbidden screen —
 * matching the backend's 403 contract.
 */
export function SystemConfigScreen() {
  const isSuperAdmin = useAuthStore((state) => state.isSuperAdmin());

  if (!isSuperAdmin) {
    return (
      <ModuleScreen title="System Configuration">
        <ForbiddenScreen />
      </ModuleScreen>
    );
  }

  return <SystemConfigContent />;
}

function SystemConfigContent() {
  const configQuery = useSystemConfig();

  return (
    <ModuleScreen
      title="System Configuration"
      description="Platform-wide settings. Restricted to Super Admins."
    >
      <QueryStateView
        isLoading={configQuery.isLoading}
        isError={configQuery.isError}
        error={configQuery.error}
        onRetry={() => void configQuery.refetch()}
        isEmpty={(configQuery.data?.length ?? 0) === 0}
        emptyMessage="No system settings available."
      >
        {configQuery.data ? <ConfigForm settings={configQuery.data} /> : null}
      </QueryStateView>
    </ModuleScreen>
  );
}

function ConfigForm({ settings }: { settings: SystemSetting[] }) {
  const update = useUpdateSystemConfig();
  const [values, setValues] = useState<Record<string, string>>({});
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    setValues(Object.fromEntries(settings.map((s) => [s.key, s.value])));
  }, [settings]);

  const dirty = useMemo(
    () => settings.some((s) => (values[s.key] ?? s.value) !== s.value),
    [settings, values],
  );

  const setValue = (key: string, value: string) => {
    setValues((prev) => ({ ...prev, [key]: value }));
  };

  const handleSave = () => {
    const changed: Record<string, string> = {};
    for (const setting of settings) {
      const next = values[setting.key] ?? setting.value;
      if (next !== setting.value) changed[setting.key] = next;
    }
    update.mutate(changed, { onSuccess: () => setSaved(true) });
  };

  return (
    <Stack spacing={2} sx={{ maxWidth: 640 }}>
      {settings.map((setting) => {
        const current = values[setting.key] ?? setting.value;
        return (
          <Box key={setting.key}>
            {setting.type === 'BOOLEAN' ? (
              <FormControlLabel
                control={
                  <Switch
                    checked={current === 'true'}
                    onChange={(event) => setValue(setting.key, String(event.target.checked))}
                  />
                }
                label={setting.label}
              />
            ) : (
              <TextField
                label={setting.label}
                type={setting.type === 'NUMBER' ? 'number' : 'text'}
                value={current}
                onChange={(event) => setValue(setting.key, event.target.value)}
                fullWidth
              />
            )}
            {setting.description ? (
              <Typography variant="caption" color="text.secondary">
                {setting.description}
              </Typography>
            ) : null}
          </Box>
        );
      })}

      {update.isError ? <Alert severity="error">{update.error.message}</Alert> : null}

      <Box>
        <Button variant="contained" onClick={handleSave} disabled={!dirty || update.isPending}>
          {update.isPending ? 'Saving…' : 'Save configuration'}
        </Button>
      </Box>

      <Snackbar
        open={saved}
        autoHideDuration={4000}
        onClose={() => setSaved(false)}
        message="System configuration saved"
      />
    </Stack>
  );
}
