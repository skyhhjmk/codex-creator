--liquibase formatted sql

--changeset codex-creator:012-wikimedia-tool-permissions
UPDATE model_profiles
SET allowed_operations = (
    SELECT jsonb_agg(value ORDER BY value)
    FROM (
        SELECT value FROM jsonb_array_elements(COALESCE(model_profiles.allowed_operations, '[]'::jsonb)) AS existing(value)
        UNION
        SELECT value FROM jsonb_array_elements('["media.inspect", "media.import"]'::jsonb)
    ) AS merged
), updated_at = CURRENT_TIMESTAMP
WHERE profile_id = 'codex-default';

--rollback UPDATE model_profiles SET allowed_operations = allowed_operations - 'media.inspect' - 'media.import' WHERE profile_id = 'codex-default';
