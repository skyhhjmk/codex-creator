--liquibase formatted sql

--changeset codex-creator:010-article-reasoning-effort
ALTER TABLE ai_article_jobs
    ADD COLUMN reasoning_effort VARCHAR(16) NOT NULL DEFAULT 'high';

--rollback ALTER TABLE ai_article_jobs DROP COLUMN reasoning_effort;
