import { apiClient } from '@api/client';
import type { UserRole } from '@stores/authStore';

/**
 * User Management bindings (Requirement 19.2).
 *
 * Endpoints (see design.md — Admin Service):
 * - GET   /admin/users                — list/search platform users
 * - PATCH /admin/users/{id}/status    — suspend/reactivate a user
 *
 * Staff invitations (email-auth Requirement 6), served by the Auth Service:
 * - GET    /admin/invitations         — open, unexpired invitations
 * - POST   /admin/invitations         — invite an email with a staff role
 * - DELETE /admin/invitations/{id}    — revoke one; its link answers 410
 */

/**
 * PENDING_VERIFICATION is an email sign-up whose code was never entered
 * (email-auth Requirement 1.3); the screen shows it as "Unverified".
 */
export type UserStatus = 'ACTIVE' | 'SUSPENDED' | 'DEACTIVATED' | 'PENDING_VERIFICATION';

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

/** An open staff invitation (email-auth Requirement 6.5). */
export interface StaffInvitation {
  id: string;
  email: string;
  role: UserRole;
  /** Account id of the admin who sent it. */
  invitedBy: string;
  invitedByName?: string | null;
  createdAt: string;
  expiresAt: string;
}

export interface InvitationPayload {
  email: string;
  role: UserRole;
}

/** GET /admin/invitations — open, unexpired invitations. */
export async function fetchInvitations(): Promise<StaffInvitation[]> {
  const { data } = await apiClient.get<StaffInvitation[]>('/admin/invitations');
  return data;
}

/**
 * POST /admin/invitations — email a single-use link. Inviting the same address
 * again replaces its open invitation (Requirement 6.2).
 */
export async function createInvitation(payload: InvitationPayload): Promise<StaffInvitation> {
  const { data } = await apiClient.post<StaffInvitation>('/admin/invitations', payload);
  return data;
}

/** DELETE /admin/invitations/{id} — revoke an invitation. */
export async function revokeInvitation(id: string): Promise<void> {
  await apiClient.delete(`/admin/invitations/${id}`);
}
