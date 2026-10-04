-- Three nullable columns on payment_transaction; existing rows need no backfill.
--
-- booking_reference: the booking's human-readable reference, read from the Booking Service's
-- payment facts when the payment is opened and sent with the provider's wallet credit, so the
-- earnings line names the job (it was always sent as null).
--
-- wallet_credit_failure: why the Provider Service refused the wallet credit for good (an unknown
-- provider, an invalid credit, a booking already credited to another provider). Written when the
-- owed marker (wallet_credit_pending_since) is cleared for such a refusal, so the sweeper stops
-- re-sending a credit that can never succeed while Finance_Admin still has it on record.
--
-- late_capture_event_id / late_captured_at: a signed SUCCEEDED gateway callback that arrived for an
-- attempt already FAILED. The capture cannot be applied as the booking's payment (a later attempt
-- may already be paid), so it is recorded here and Finance_Admin is alerted to refund it.
ALTER TABLE payment.payment_transaction ADD COLUMN booking_reference character varying(64);
ALTER TABLE payment.payment_transaction ADD COLUMN wallet_credit_failure character varying(512);
ALTER TABLE payment.payment_transaction ADD COLUMN late_capture_event_id character varying(128);
ALTER TABLE payment.payment_transaction ADD COLUMN late_captured_at timestamp(6) with time zone;
