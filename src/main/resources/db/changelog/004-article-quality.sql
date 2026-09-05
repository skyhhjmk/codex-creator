--liquibase formatted sql

--changeset codex-creator:004-article-quality
ALTER TABLE automation_tasks
    ADD COLUMN prompt_version VARCHAR(64) NOT NULL DEFAULT '1';

ALTER TABLE ai_article_jobs
    ADD COLUMN generation_attempt INTEGER NOT NULL DEFAULT 1;
ALTER TABLE ai_article_jobs
    ADD COLUMN quality_attempt INTEGER NOT NULL DEFAULT 1;
ALTER TABLE ai_article_jobs
    ADD COLUMN quality_report JSONB;

--rollback ALTER TABLE ai_article_jobs DROP COLUMN quality_report;
--rollback ALTER TABLE ai_article_jobs DROP COLUMN quality_attempt;
--rollback ALTER TABLE ai_article_jobs DROP COLUMN generation_attempt;
--rollback ALTER TABLE automation_tasks DROP COLUMN prompt_version;
