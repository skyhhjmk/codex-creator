--liquibase formatted sql

--changeset codex-creator:013-article-model-name
ALTER TABLE ai_article_jobs
    ADD COLUMN model_name VARCHAR(200);

UPDATE ai_article_jobs jobs
SET model_name = profiles.display_name
FROM model_profiles profiles
WHERE jobs.model_profile_id = profiles.profile_id
  AND jobs.model_name IS NULL;

--rollback ALTER TABLE ai_article_jobs DROP COLUMN model_name;
