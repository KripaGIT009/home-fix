-- Postgres init for the local HomeFix stack.
-- Hibernate ddl-auto=update creates TABLES but not SCHEMAS, and each service sets
-- hibernate.default_schema to its own name. Create every schema up front so the
-- services can start against a fresh database.
-- Shared infrastructure schema for the transactional outbox. Every producer writes its
-- outbox_event rows here and the Outbox Processor drains this one table, so the relay and
-- the producers cannot disagree about where events live.
CREATE SCHEMA IF NOT EXISTS outbox;

CREATE SCHEMA IF NOT EXISTS auth;
CREATE SCHEMA IF NOT EXISTS booking;
CREATE SCHEMA IF NOT EXISTS customer;
CREATE SCHEMA IF NOT EXISTS provider;
CREATE SCHEMA IF NOT EXISTS catalog;
CREATE SCHEMA IF NOT EXISTS payment;
CREATE SCHEMA IF NOT EXISTS dispatch;
CREATE SCHEMA IF NOT EXISTS location;
CREATE SCHEMA IF NOT EXISTS verification;
CREATE SCHEMA IF NOT EXISTS invoice;
CREATE SCHEMA IF NOT EXISTS notification;
CREATE SCHEMA IF NOT EXISTS complaint;
CREATE SCHEMA IF NOT EXISTS chat;
CREATE SCHEMA IF NOT EXISTS rating;
CREATE SCHEMA IF NOT EXISTS promotion;
CREATE SCHEMA IF NOT EXISTS admin;
