-- Baseline schema for chat-service (schema "chat").
--
-- Generated with pg_dump --schema-only from a database Hibernate populated from this service's
-- entities, so it matches what ddl-auto=validate checks. Flyway creates the schema itself
-- (spring.flyway.schemas). On a database that already has these tables (created by the old
-- ddl-auto=update local setup) baseline-on-migrate records this version as applied instead.
--
-- Never edit this file once released: add V2__..., V3__... for every later change.

CREATE TABLE chat.chat_channel (
    booking_id uuid NOT NULL,
    activated_at timestamp(6) with time zone NOT NULL,
    booking_created_at timestamp(6) with time zone NOT NULL,
    customer_id uuid NOT NULL,
    deactivated_at timestamp(6) with time zone,
    provider_id uuid NOT NULL,
    status character varying(20) NOT NULL,
    CONSTRAINT chat_channel_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'DEACTIVATED'::character varying])::text[])))
);

CREATE TABLE chat.chat_message (
    id uuid NOT NULL,
    body character varying(4000) NOT NULL,
    booking_id uuid NOT NULL,
    retain_until timestamp(6) with time zone NOT NULL,
    sender_id uuid NOT NULL,
    sent_at timestamp(6) with time zone NOT NULL
);

ALTER TABLE ONLY chat.chat_channel
    ADD CONSTRAINT chat_channel_pkey PRIMARY KEY (booking_id);

ALTER TABLE ONLY chat.chat_message
    ADD CONSTRAINT chat_message_pkey PRIMARY KEY (id);

CREATE INDEX idx_chat_message_booking ON chat.chat_message USING btree (booking_id, sent_at);

CREATE INDEX idx_chat_message_retain_until ON chat.chat_message USING btree (retain_until);
