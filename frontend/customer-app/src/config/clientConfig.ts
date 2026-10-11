import { useQuery } from '@tanstack/react-query';
import { apiClient } from '@api/client';

/**
 * Platform settings read from the backend's `config.app_config` table.
 *
 * GET /config/client (public, served by the Catalog Service through the API
 * Gateway) returns the rows marked `exposed_to_clients` as an object of
 * Spring property name to value, e.g.
 * `{ "homefix.apps.provider-app-url": "https://pro.homefix.example" }`.
 * The values are changed with SQL on the server (see docs/ARCHITECTURE.md),
 * so a deployment needs no rebuild of this app to move them.
 */
export type ClientConfig = Readonly<Record<string, string>>;

export const CLIENT_CONFIG_KEY = ['config', 'client'] as const;

/** Matches the endpoint's own 5-minute Cache-Control. */
const CLIENT_CONFIG_STALE_TIME_MS = 5 * 60_000;

export async function fetchClientConfig(): Promise<ClientConfig> {
  const { data } = await apiClient.get<ClientConfig>('/config/client');
  return data;
}

/**
 * One setting from the table, or `fallback` while it loads, when the request
 * fails, or when the row does not exist. The fallback keeps links usable when
 * the platform is unreachable.
 */
export function useClientSetting(key: string, fallback: string): string {
  const { data } = useQuery({
    queryKey: CLIENT_CONFIG_KEY,
    queryFn: fetchClientConfig,
    staleTime: CLIENT_CONFIG_STALE_TIME_MS,
    retry: 1,
  });
  return data?.[key] ?? fallback;
}
