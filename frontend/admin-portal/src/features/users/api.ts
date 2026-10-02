import { apiClient } from '@api/client';
import type { UserRole } from '@stores/authStore';

/**
 * User Management bindings (Requirement 19.2).
 *
 * Endpoints (see design.md — Admin Service):
 * - GET   /admin/users                — list/search platform users
 * - PATCH /admin/users/{id}/status    — suspend/reactivate a user
 */

export type UserStatus = 'ACTIVE' | 'SUSPENDED' | 'DEACTIVATED';

export interface AdminUser {
  id: string;
  /** Null/absent until the account has a profile name; the screen shows a dash. */
  displayName?: string | null;
  mobileNumber: string;
  email?: string | null;
  roles: UserRole[];
  status: UserStatus;
  createdAt: string;
}

/** GET /admin/users — platform users, optionally filtered by a search term. */
export async function fetchUsers(search?: string): Promise<AdminUser[]> {
  const { data } = await apiClient.get<AdminUser[]>('/admin/users', {
    params: search ? { search } : undefined,
  });
  return data;
}

/** PATCH /admin/users/{id}/status — change a user's account status. */
export async function updateUserStatus(id: string, status: UserStatus): Promise<AdminUser> {
  const { data } = await apiClient.patch<AdminUser>(`/admin/users/${id}/status`, { status });
  return data;
}
