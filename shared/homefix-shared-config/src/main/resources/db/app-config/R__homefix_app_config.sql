-- Platform settings: config.app_config holds one row per Spring property.
--
-- config_key          a Spring property name, e.g. homefix.apps.admin-portal-url
-- config_value        its value
-- exposed_to_clients  also served to the web apps on GET /config/client, without sign-in; mark
--                     only values that are safe to publish (never secrets)
--
-- Every service loads the rows at startup (AppConfigEnvironmentPostProcessor in
-- homefix-shared-config): they override the service's YAML but not its environment variables.
-- Changing a row is plain SQL, then a restart of the deployables that read it, e.g.
--
--   UPDATE config.app_config
--      SET config_value = 'https://admin.homefix.example', updated_at = now()
--    WHERE config_key = 'homefix.apps.admin-portal-url';
--
-- It is a repeatable migration in every service's spring.flyway.locations, and every statement
-- is idempotent: the rows below are defaults for a new database and never overwrite a value
-- already there. Services keep their Flyway history in their own schemas, so the advisory lock
-- (not Flyway's) serialises concurrent cold starts.

SELECT pg_advisory_xact_lock(hashtext('homefix-shared-config-schema'));

CREATE SCHEMA IF NOT EXISTS config;

CREATE TABLE IF NOT EXISTS config.app_config (
    config_key         varchar(200)                NOT NULL,
    config_value       text                        NOT NULL,
    exposed_to_clients boolean                     NOT NULL DEFAULT false,
    description        varchar(500),
    updated_at         timestamp(6) with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (config_key)
);

-- The web apps' public addresses (the local stack's defaults).
INSERT INTO config.app_config (config_key, config_value, exposed_to_clients, description) VALUES
    ('homefix.apps.customer-app-url', 'http://localhost:5173', true,
     'Public base URL of the Customer App.'),
    ('homefix.apps.provider-app-url', 'http://localhost:5174', true,
     'Public base URL of the Provider App (the Customer App''s "Join as a professional" link).'),
    ('homefix.apps.admin-portal-url', 'http://localhost:5175', true,
     'Public base URL of the Admin Portal (the link in staff invitation emails).')
ON CONFLICT (config_key) DO NOTHING;
