--liquibase formatted sql

--changeset codex-creator:007-content-promotion
ALTER TABLE topic_automation_settings
    ADD COLUMN promotion_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE topic_automation_settings
    ADD COLUMN promotion_markdown TEXT;

--rollback ALTER TABLE topic_automation_settings DROP COLUMN promotion_markdown;
--rollback ALTER TABLE topic_automation_settings DROP COLUMN promotion_enabled;
