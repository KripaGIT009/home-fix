-- Razorpay webhooks name the Razorpay order, which is the transaction's gateway_reference, so the
-- webhook finds its transaction by that column. Not unique: references are per gateway, and older
-- rows may carry none.
CREATE INDEX IF NOT EXISTS ix_payment_transaction_gateway_reference
    ON payment.payment_transaction USING btree (gateway_reference);
