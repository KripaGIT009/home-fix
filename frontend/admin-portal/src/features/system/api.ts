import { apiClient } from '@api/client';

/**
 * System Configuration bindings (Requirement 19.2, Requirement 19.6/19.7).
 *
 * Platform-wide settings editable only by SUPER_ADMIN. The backend returns 403
 * for ADMIN callers; the UI additionally hides/guards this module (RBAC in the
 * sidebar + route guard), so a 403 here is a defence-in-depth backstop.
 *
 * Endpoints (see design.md — Admin Service):
 * - GET /admin/system-config       — all system configuration settings
 * - PUT /admin/system-config       — update settings (batch)
 */

export type SettingType = 'STRING' | 'NUMBER' | 'BOOLEAN';

export interface SystemSetting {
  key: string;
  label: string;
  description?: string;
  type: SettingType;
  value: string;
}

/** GET /admin/system-config — all system settings. */
export async function fetchSystemConfig(): Promise<SystemSetting[]> {
  const { data } = await apiClient.get<SystemSetting[]>('/admin/system-config');
  return data;
}

/** PUT /admin/system-config — persist updated settings as key/value pairs. */
export async function updateSystemConfig(
  updates: Record<string, string>,
): Promise<SystemSetting[]> {
  const { data } = await apiClient.put<SystemSetting[]>('/admin/system-config', { updates });
  return data;
}
