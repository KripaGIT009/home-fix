import { apiClient } from '@api/client';

/**
 * Notification Templates bindings (Requirement 19.2, Requirement 14).
 *
 * Admins manage the message templates the Notification Service renders across
 * channels (push, SMS, email, in-app). Each template has a channel, a subject
 * (for email), and a body with {{placeholders}}.
 *
 * Endpoints (see design.md — Admin Service / Notification Service):
 * - GET /admin/notification-templates       — list templates
 * - PUT /admin/notification-templates/{id}   — update a template
 */

export type NotificationChannel = 'PUSH' | 'SMS' | 'EMAIL' | 'IN_APP';

export interface NotificationTemplate {
  id: string;
  key: string;
  name: string;
  channel: NotificationChannel;
  /** Present for EMAIL templates. */
  subject?: string;
  body: string;
}

export interface UpdateTemplatePayload {
  subject?: string;
  body: string;
}

/** GET /admin/notification-templates — all notification templates. */
export async function fetchTemplates(): Promise<NotificationTemplate[]> {
  const { data } = await apiClient.get<NotificationTemplate[]>('/admin/notification-templates');
  return data;
}

/** PUT /admin/notification-templates/{id} — update a template. */
export async function updateTemplate(
  id: string,
  payload: UpdateTemplatePayload,
): Promise<NotificationTemplate> {
  const { data } = await apiClient.put<NotificationTemplate>(
    `/admin/notification-templates/${id}`,
    payload,
  );
  return data;
}
