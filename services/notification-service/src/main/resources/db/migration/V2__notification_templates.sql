-- Admin-editable notification templates (Requirement 19.2).
--
-- One row per built-in template and delivery channel. The Admin Portal edits a row's subject and
-- body; the template set, ids, names and channels are the service's built-in catalogue
-- (BuiltInTemplates) and are not editable. Seeded below with EXACTLY the texts the service sent
-- before this table existed, so behaviour is unchanged until an admin edits a template; a missing
-- row falls back to the same built-in text. NotificationTemplateSeedTest keeps this seed and the
-- catalogue in step.
--
--   id            EVENT.AUDIENCE[.VARIANT].CHANNEL, e.g. BOOKING_CANCELLED.CUSTOMER.SEARCHING_FAILED.SMS
--   template_key  EVENT.AUDIENCE[.VARIANT]
--   subject       push / in-app heading or email subject; NULL for SMS, which has none
--   body          text with {{placeholders}}; only those the event supplies are accepted
--   updated_by    the admin who last edited the row; NULL for the seeded text

CREATE TABLE notification.notification_template (
    id character varying(120) NOT NULL,
    template_key character varying(100) NOT NULL,
    name character varying(150) NOT NULL,
    channel character varying(20) NOT NULL,
    subject character varying(200),
    body character varying(1000) NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    updated_by uuid,
    CONSTRAINT notification_template_pkey PRIMARY KEY (id),
    CONSTRAINT notification_template_key_channel_key UNIQUE (template_key, channel),
    CONSTRAINT notification_template_channel_check CHECK (((channel)::text = ANY ((ARRAY['PUSH'::character varying, 'SMS'::character varying, 'EMAIL'::character varying, 'IN_APP'::character varying])::text[])))
);

INSERT INTO notification.notification_template (id, template_key, name, channel, subject, body, updated_at) VALUES
    ('BOOKING_CREATED.CUSTOMER.PUSH', 'BOOKING_CREATED.CUSTOMER', 'Booking created (customer) — Push', 'PUSH', 'Booking confirmed', 'We received {{bookingReference}} and are finding a professional for you.', now()),
    ('BOOKING_CREATED.CUSTOMER.SMS', 'BOOKING_CREATED.CUSTOMER', 'Booking created (customer) — SMS', 'SMS', NULL, 'We received {{bookingReference}} and are finding a professional for you.', now()),
    ('BOOKING_CREATED.CUSTOMER.EMAIL', 'BOOKING_CREATED.CUSTOMER', 'Booking created (customer) — Email', 'EMAIL', 'Booking confirmed', 'We received {{bookingReference}} and are finding a professional for you.', now()),
    ('BOOKING_CREATED.CUSTOMER.IN_APP', 'BOOKING_CREATED.CUSTOMER', 'Booking created (customer) — In-app', 'IN_APP', 'Booking confirmed', 'We received {{bookingReference}} and are finding a professional for you.', now()),
    ('PROVIDER_ASSIGNED.PROVIDER.PUSH', 'PROVIDER_ASSIGNED.PROVIDER', 'Provider assigned (provider) — Push', 'PUSH', 'New job assigned', 'You have been assigned {{bookingReference}}. Open the app for the details.', now()),
    ('PROVIDER_ASSIGNED.PROVIDER.SMS', 'PROVIDER_ASSIGNED.PROVIDER', 'Provider assigned (provider) — SMS', 'SMS', NULL, 'You have been assigned {{bookingReference}}. Open the app for the details.', now()),
    ('PROVIDER_ASSIGNED.PROVIDER.IN_APP', 'PROVIDER_ASSIGNED.PROVIDER', 'Provider assigned (provider) — In-app', 'IN_APP', 'New job assigned', 'You have been assigned {{bookingReference}}. Open the app for the details.', now()),
    ('PROVIDER_ASSIGNED.CUSTOMER.PUSH', 'PROVIDER_ASSIGNED.CUSTOMER', 'Provider assigned (customer) — Push', 'PUSH', 'Professional assigned', 'A professional has been assigned to {{bookingReference}}.', now()),
    ('PROVIDER_ASSIGNED.CUSTOMER.IN_APP', 'PROVIDER_ASSIGNED.CUSTOMER', 'Provider assigned (customer) — In-app', 'IN_APP', 'Professional assigned', 'A professional has been assigned to {{bookingReference}}.', now()),
    ('PROVIDER_ACCEPTED.CUSTOMER.PUSH', 'PROVIDER_ACCEPTED.CUSTOMER', 'Provider accepted (customer) — Push', 'PUSH', 'Professional on the way', 'Your professional accepted {{bookingReference}} and is on the way.', now()),
    ('PROVIDER_ACCEPTED.CUSTOMER.SMS', 'PROVIDER_ACCEPTED.CUSTOMER', 'Provider accepted (customer) — SMS', 'SMS', NULL, 'Your professional accepted {{bookingReference}} and is on the way.', now()),
    ('PROVIDER_ACCEPTED.CUSTOMER.IN_APP', 'PROVIDER_ACCEPTED.CUSTOMER', 'Provider accepted (customer) — In-app', 'IN_APP', 'Professional on the way', 'Your professional accepted {{bookingReference}} and is on the way.', now()),
    ('PROVIDER_REJECTED.CUSTOMER.IN_APP', 'PROVIDER_REJECTED.CUSTOMER', 'Provider declined (customer) — In-app', 'IN_APP', 'Finding another professional', 'We are matching {{bookingReference}} with another professional.', now()),
    ('PROVIDER_ARRIVING.CUSTOMER.PUSH', 'PROVIDER_ARRIVING.CUSTOMER', 'Provider arriving (customer) — Push', 'PUSH', 'Professional arriving soon', 'Your professional for {{bookingReference}} is arriving soon.', now()),
    ('PROVIDER_ARRIVING.CUSTOMER.IN_APP', 'PROVIDER_ARRIVING.CUSTOMER', 'Provider arriving (customer) — In-app', 'IN_APP', 'Professional arriving soon', 'Your professional for {{bookingReference}} is arriving soon.', now()),
    ('PROVIDER_ARRIVED.CUSTOMER.PUSH', 'PROVIDER_ARRIVED.CUSTOMER', 'Provider arrived (customer) — Push', 'PUSH', 'Professional arrived', 'Your professional for {{bookingReference}} has arrived.', now()),
    ('PROVIDER_ARRIVED.CUSTOMER.SMS', 'PROVIDER_ARRIVED.CUSTOMER', 'Provider arrived (customer) — SMS', 'SMS', NULL, 'Your professional for {{bookingReference}} has arrived.', now()),
    ('PROVIDER_ARRIVED.CUSTOMER.IN_APP', 'PROVIDER_ARRIVED.CUSTOMER', 'Provider arrived (customer) — In-app', 'IN_APP', 'Professional arrived', 'Your professional for {{bookingReference}} has arrived.', now()),
    ('JOB_STARTED.CUSTOMER.PUSH', 'JOB_STARTED.CUSTOMER', 'Job started (customer) — Push', 'PUSH', 'Job started', 'Work on {{bookingReference}} has started.', now()),
    ('JOB_STARTED.CUSTOMER.IN_APP', 'JOB_STARTED.CUSTOMER', 'Job started (customer) — In-app', 'IN_APP', 'Job started', 'Work on {{bookingReference}} has started.', now()),
    ('JOB_COMPLETED.CUSTOMER.PUSH', 'JOB_COMPLETED.CUSTOMER', 'Job completed (customer) — Push', 'PUSH', 'Job completed', 'Work on {{bookingReference}} is complete.', now()),
    ('JOB_COMPLETED.CUSTOMER.SMS', 'JOB_COMPLETED.CUSTOMER', 'Job completed (customer) — SMS', 'SMS', NULL, 'Work on {{bookingReference}} is complete.', now()),
    ('JOB_COMPLETED.CUSTOMER.EMAIL', 'JOB_COMPLETED.CUSTOMER', 'Job completed (customer) — Email', 'EMAIL', 'Job completed', 'Work on {{bookingReference}} is complete.', now()),
    ('JOB_COMPLETED.CUSTOMER.IN_APP', 'JOB_COMPLETED.CUSTOMER', 'Job completed (customer) — In-app', 'IN_APP', 'Job completed', 'Work on {{bookingReference}} is complete.', now()),
    ('PAYMENT_COMPLETED.PROVIDER.PUSH', 'PAYMENT_COMPLETED.PROVIDER', 'Payment completed (provider) — Push', 'PUSH', 'Payment received', 'The customer''s payment for {{bookingReference}} is complete. Your earnings will be included in your next settlement.', now()),
    ('PAYMENT_COMPLETED.PROVIDER.IN_APP', 'PAYMENT_COMPLETED.PROVIDER', 'Payment completed (provider) — In-app', 'IN_APP', 'Payment received', 'The customer''s payment for {{bookingReference}} is complete. Your earnings will be included in your next settlement.', now()),
    ('PAYMENT_COMPLETED.CUSTOMER.PUSH', 'PAYMENT_COMPLETED.CUSTOMER', 'Payment completed (customer) — Push', 'PUSH', 'Payment received', 'Payment for {{bookingReference}} was successful. Your invoice is ready.', now()),
    ('PAYMENT_COMPLETED.CUSTOMER.SMS', 'PAYMENT_COMPLETED.CUSTOMER', 'Payment completed (customer) — SMS', 'SMS', NULL, 'Payment for {{bookingReference}} was successful. Your invoice is ready.', now()),
    ('PAYMENT_COMPLETED.CUSTOMER.EMAIL', 'PAYMENT_COMPLETED.CUSTOMER', 'Payment completed (customer) — Email', 'EMAIL', 'Payment received', 'Payment for {{bookingReference}} was successful. Your invoice is ready.', now()),
    ('PAYMENT_COMPLETED.CUSTOMER.IN_APP', 'PAYMENT_COMPLETED.CUSTOMER', 'Payment completed (customer) — In-app', 'IN_APP', 'Payment received', 'Payment for {{bookingReference}} was successful. Your invoice is ready.', now()),
    ('BOOKING_CANCELLED.PROVIDER.PUSH', 'BOOKING_CANCELLED.PROVIDER', 'Booking cancelled (provider) — Push', 'PUSH', 'Job cancelled', '{{bookingReference}} has been cancelled. You do not need to attend.', now()),
    ('BOOKING_CANCELLED.PROVIDER.SMS', 'BOOKING_CANCELLED.PROVIDER', 'Booking cancelled (provider) — SMS', 'SMS', NULL, '{{bookingReference}} has been cancelled. You do not need to attend.', now()),
    ('BOOKING_CANCELLED.PROVIDER.IN_APP', 'BOOKING_CANCELLED.PROVIDER', 'Booking cancelled (provider) — In-app', 'IN_APP', 'Job cancelled', '{{bookingReference}} has been cancelled. You do not need to attend.', now()),
    ('BOOKING_CANCELLED.CUSTOMER.SEARCHING_FAILED.PUSH', 'BOOKING_CANCELLED.CUSTOMER.SEARCHING_FAILED', 'No professional found (customer) — Push', 'PUSH', 'No professional available', 'We could not find a professional for {{bookingReference}}, so the booking has been closed.', now()),
    ('BOOKING_CANCELLED.CUSTOMER.SEARCHING_FAILED.SMS', 'BOOKING_CANCELLED.CUSTOMER.SEARCHING_FAILED', 'No professional found (customer) — SMS', 'SMS', NULL, 'We could not find a professional for {{bookingReference}}, so the booking has been closed.', now()),
    ('BOOKING_CANCELLED.CUSTOMER.SEARCHING_FAILED.EMAIL', 'BOOKING_CANCELLED.CUSTOMER.SEARCHING_FAILED', 'No professional found (customer) — Email', 'EMAIL', 'No professional available', 'We could not find a professional for {{bookingReference}}, so the booking has been closed.', now()),
    ('BOOKING_CANCELLED.CUSTOMER.SEARCHING_FAILED.IN_APP', 'BOOKING_CANCELLED.CUSTOMER.SEARCHING_FAILED', 'No professional found (customer) — In-app', 'IN_APP', 'No professional available', 'We could not find a professional for {{bookingReference}}, so the booking has been closed.', now()),
    ('BOOKING_CANCELLED.CUSTOMER.PUSH', 'BOOKING_CANCELLED.CUSTOMER', 'Booking cancelled (customer) — Push', 'PUSH', 'Booking cancelled', '{{bookingReference}} has been cancelled.', now()),
    ('BOOKING_CANCELLED.CUSTOMER.SMS', 'BOOKING_CANCELLED.CUSTOMER', 'Booking cancelled (customer) — SMS', 'SMS', NULL, '{{bookingReference}} has been cancelled.', now()),
    ('BOOKING_CANCELLED.CUSTOMER.EMAIL', 'BOOKING_CANCELLED.CUSTOMER', 'Booking cancelled (customer) — Email', 'EMAIL', 'Booking cancelled', '{{bookingReference}} has been cancelled.', now()),
    ('BOOKING_CANCELLED.CUSTOMER.IN_APP', 'BOOKING_CANCELLED.CUSTOMER', 'Booking cancelled (customer) — In-app', 'IN_APP', 'Booking cancelled', '{{bookingReference}} has been cancelled.', now()),
    ('REVIEW_SUBMITTED.REVIEWER.PUSH', 'REVIEW_SUBMITTED.REVIEWER', 'Review submitted (reviewer) — Push', 'PUSH', 'Review submitted', 'Thanks — your review for {{bookingReference}} was submitted.', now()),
    ('REVIEW_SUBMITTED.REVIEWER.IN_APP', 'REVIEW_SUBMITTED.REVIEWER', 'Review submitted (reviewer) — In-app', 'IN_APP', 'Review submitted', 'Thanks — your review for {{bookingReference}} was submitted.', now()),
    ('REVIEW_SUBMITTED.REVIEWEE.PUSH', 'REVIEW_SUBMITTED.REVIEWEE', 'Review received (reviewee) — Push', 'PUSH', 'New review', 'You received a new review for {{bookingReference}}.', now()),
    ('REVIEW_SUBMITTED.REVIEWEE.IN_APP', 'REVIEW_SUBMITTED.REVIEWEE', 'Review received (reviewee) — In-app', 'IN_APP', 'New review', 'You received a new review for {{bookingReference}}.', now()),
    ('COMPLAINT_CREATED.CUSTOMER.PUSH', 'COMPLAINT_CREATED.CUSTOMER', 'Complaint received (customer) — Push', 'PUSH', 'Complaint received', 'We received your complaint about {{bookingReference}}. A support agent has been assigned and will be in touch.', now()),
    ('COMPLAINT_CREATED.CUSTOMER.SMS', 'COMPLAINT_CREATED.CUSTOMER', 'Complaint received (customer) — SMS', 'SMS', NULL, 'We received your complaint about {{bookingReference}}. A support agent has been assigned and will be in touch.', now()),
    ('COMPLAINT_CREATED.CUSTOMER.EMAIL', 'COMPLAINT_CREATED.CUSTOMER', 'Complaint received (customer) — Email', 'EMAIL', 'Complaint received', 'We received your complaint about {{bookingReference}}. A support agent has been assigned and will be in touch.', now()),
    ('COMPLAINT_CREATED.CUSTOMER.IN_APP', 'COMPLAINT_CREATED.CUSTOMER', 'Complaint received (customer) — In-app', 'IN_APP', 'Complaint received', 'We received your complaint about {{bookingReference}}. A support agent has been assigned and will be in touch.', now()),
    ('COMPLAINT_STATUS_CHANGED.CUSTOMER.PUSH', 'COMPLAINT_STATUS_CHANGED.CUSTOMER', 'Complaint status changed (customer) — Push', 'PUSH', 'Complaint update', 'Your complaint about {{bookingReference}} is now {{complaintStatus}}.', now()),
    ('COMPLAINT_STATUS_CHANGED.CUSTOMER.IN_APP', 'COMPLAINT_STATUS_CHANGED.CUSTOMER', 'Complaint status changed (customer) — In-app', 'IN_APP', 'Complaint update', 'Your complaint about {{bookingReference}} is now {{complaintStatus}}.', now());
