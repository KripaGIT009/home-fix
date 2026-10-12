-- ProviderAssigned texts for jobs assigned by a partner agency (Tenant) (Requirements MT-5.3, MT-6).
--
-- ProviderAssigned is published only when a booking comes to rest in PROVIDER_ASSIGNED, which now
-- means a Tenant assigned the job and the provider still has to accept or decline it; automatic
-- dispatch passes straight through to PROVIDER_ACCEPTED without the event. The seeded texts are
-- brought in line with that ("accept or decline", "once they confirm"), and a provider variant
-- that names the agency is added, picked when the event carries a tenantName:
--
--   PROVIDER_ASSIGNED.PROVIDER.TENANT   "... has been assigned to you by {{tenantName}} ..."
--
-- Only rows still holding the seeded text are updated (updated_by IS NULL): an admin's edit
-- wins. The new rows are inserted unless they already exist. NotificationTemplateSeedTest applies
-- this file on top of V2 and checks the result equals BuiltInTemplates.

UPDATE notification.notification_template SET subject = 'New job assigned', body = 'You have been assigned {{bookingReference}}. Open the app to accept or decline it.', updated_at = now() WHERE id = 'PROVIDER_ASSIGNED.PROVIDER.PUSH' AND updated_by IS NULL;
UPDATE notification.notification_template SET subject = NULL, body = 'You have been assigned {{bookingReference}}. Open the app to accept or decline it.', updated_at = now() WHERE id = 'PROVIDER_ASSIGNED.PROVIDER.SMS' AND updated_by IS NULL;
UPDATE notification.notification_template SET subject = 'New job assigned', body = 'You have been assigned {{bookingReference}}. Open the app to accept or decline it.', updated_at = now() WHERE id = 'PROVIDER_ASSIGNED.PROVIDER.IN_APP' AND updated_by IS NULL;
UPDATE notification.notification_template SET subject = 'Professional assigned', body = 'A professional has been assigned to {{bookingReference}}. We will let you know as soon as they confirm.', updated_at = now() WHERE id = 'PROVIDER_ASSIGNED.CUSTOMER.PUSH' AND updated_by IS NULL;
UPDATE notification.notification_template SET subject = 'Professional assigned', body = 'A professional has been assigned to {{bookingReference}}. We will let you know as soon as they confirm.', updated_at = now() WHERE id = 'PROVIDER_ASSIGNED.CUSTOMER.IN_APP' AND updated_by IS NULL;

INSERT INTO notification.notification_template (id, template_key, name, channel, subject, body, updated_at) VALUES
    ('PROVIDER_ASSIGNED.PROVIDER.TENANT.PUSH', 'PROVIDER_ASSIGNED.PROVIDER.TENANT', 'Provider assigned by a partner agency (provider) — Push', 'PUSH', 'New job from {{tenantName}}', '{{bookingReference}} has been assigned to you by {{tenantName}}. Open the app to accept or decline it.', now()),
    ('PROVIDER_ASSIGNED.PROVIDER.TENANT.SMS', 'PROVIDER_ASSIGNED.PROVIDER.TENANT', 'Provider assigned by a partner agency (provider) — SMS', 'SMS', NULL, '{{bookingReference}} has been assigned to you by {{tenantName}}. Open the app to accept or decline it.', now()),
    ('PROVIDER_ASSIGNED.PROVIDER.TENANT.IN_APP', 'PROVIDER_ASSIGNED.PROVIDER.TENANT', 'Provider assigned by a partner agency (provider) — In-app', 'IN_APP', 'New job from {{tenantName}}', '{{bookingReference}} has been assigned to you by {{tenantName}}. Open the app to accept or decline it.', now())
ON CONFLICT (id) DO NOTHING;
