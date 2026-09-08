--liquibase formatted sql

--changeset codex-creator:011-wikimedia-image-evidence
ALTER TABLE ai_article_job_evidence ADD COLUMN source_page VARCHAR(2048);
ALTER TABLE ai_article_job_evidence ADD COLUMN license_name VARCHAR(512);
ALTER TABLE ai_article_job_evidence ADD COLUMN license_url VARCHAR(2048);
ALTER TABLE ai_article_job_evidence ADD COLUMN attribution TEXT;

UPDATE model_profiles
SET allowed_operations = (
    SELECT jsonb_agg(value ORDER BY value)
    FROM (
        SELECT value FROM jsonb_array_elements(COALESCE(model_profiles.allowed_operations, '[]'::jsonb)) AS existing(value)
        UNION
        SELECT value FROM jsonb_array_elements('["media.search"]'::jsonb)
    ) AS merged
), updated_at = CURRENT_TIMESTAMP
WHERE profile_id = 'codex-default';

--rollback ALTER TABLE ai_article_job_evidence DROP COLUMN attribution;
--rollback ALTER TABLE ai_article_job_evidence DROP COLUMN license_url;
--rollback ALTER TABLE ai_article_job_evidence DROP COLUMN license_name;
--rollback ALTER TABLE ai_article_job_evidence DROP COLUMN source_page;
--rollback UPDATE model_profiles SET allowed_operations = allowed_operations - 'media.search' WHERE profile_id = 'codex-default';
