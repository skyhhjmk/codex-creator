-- liquibase formatted sql
-- changeset codex-creator:014-article-repost-policy

ALTER TABLE ai_article_jobs
    ADD COLUMN IF NOT EXISTS repost_policy_code VARCHAR(64) NOT NULL DEFAULT 'REQUEST_REQUIRED';

-- rollback ALTER TABLE ai_article_jobs DROP COLUMN repost_policy_code;
