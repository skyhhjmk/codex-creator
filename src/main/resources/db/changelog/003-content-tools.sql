--liquibase formatted sql

--changeset codex-creator:003-content-tools
UPDATE model_profiles
SET allowed_operations = (
    SELECT jsonb_agg(value ORDER BY value)
    FROM (
        SELECT value
        FROM jsonb_array_elements(COALESCE(model_profiles.allowed_operations, '[]'::jsonb)) AS existing(value)
        UNION
        SELECT value
        FROM jsonb_array_elements(
            '["category.read", "category.write", "tag.read", "tag.write", "media.upload"]'::jsonb
        ) AS additions(value)
    ) AS merged
), updated_at = CURRENT_TIMESTAMP
WHERE profile_id = 'codex-default';

--rollback UPDATE model_profiles
--rollback SET allowed_operations = allowed_operations - 'category.read' - 'category.write'
--rollback     - 'tag.read' - 'tag.write' - 'media.upload'
--rollback WHERE profile_id = 'codex-default';
