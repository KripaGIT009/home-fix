-- System Configuration values (Requirement 19.2), so a SUPER_ADMIN's changes survive a restart.
--
-- The known settings, their types and defaults are code (SystemSettingRegistry); a row exists
-- only for a setting that has been changed from its default. The change history (who, when,
-- before/after) is in admin.audit_log, so this table keeps only the current value and its author.

CREATE TABLE admin.system_setting (
    setting_key character varying(100) NOT NULL,
    setting_value character varying(1000) NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    updated_by uuid NOT NULL,
    CONSTRAINT system_setting_pkey PRIMARY KEY (setting_key)
);
