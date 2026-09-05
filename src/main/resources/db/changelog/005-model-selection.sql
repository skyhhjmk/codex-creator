--liquibase formatted sql

--changeset codex-creator:005-model-selection
ALTER TABLE topic_discovery_runs
    ADD COLUMN model_profile_id VARCHAR(128) REFERENCES model_profiles(profile_id) ON DELETE SET NULL;
ALTER TABLE ai_article_jobs
    ADD COLUMN model_profile_id VARCHAR(128) REFERENCES model_profiles(profile_id) ON DELETE SET NULL;

INSERT INTO model_profiles
    (profile_id, vendor, model_id, display_name, reasoning_effort, capabilities, allowed_operations, enabled, created_at, updated_at)
VALUES
    ('codex-gpt-5-6-sol', 'OpenAI-Codex', 'gpt-5.6-sol', 'GPT-5.6 Sol', 'high', '{"text":true,"structuredOutput":true}'::jsonb, '["topic","article"]'::jsonb, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('codex-gpt-5-6-terra', 'OpenAI-Codex', 'gpt-5.6-terra', 'GPT-5.6 Terra', 'high', '{"text":true,"structuredOutput":true}'::jsonb, '["topic","article"]'::jsonb, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('codex-gpt-5-6-luna', 'OpenAI-Codex', 'gpt-5.6-luna', 'GPT-5.6 Luna', 'medium', '{"text":true,"structuredOutput":true}'::jsonb, '["topic","article"]'::jsonb, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('codex-gpt-5-5', 'OpenAI-Codex', 'gpt-5.5', 'GPT-5.5', 'high', '{"text":true,"structuredOutput":true}'::jsonb, '["topic","article"]'::jsonb, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('codex-gpt-5-4', 'OpenAI-Codex', 'gpt-5.4', 'GPT-5.4', 'high', '{"text":true,"structuredOutput":true}'::jsonb, '["topic","article"]'::jsonb, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (profile_id) DO UPDATE SET
    model_id = EXCLUDED.model_id,
    display_name = EXCLUDED.display_name,
    allowed_operations = EXCLUDED.allowed_operations,
    updated_at = CURRENT_TIMESTAMP;

--rollback DELETE FROM model_profiles WHERE profile_id IN ('codex-gpt-5-6-sol','codex-gpt-5-6-terra','codex-gpt-5-6-luna','codex-gpt-5-5','codex-gpt-5-4');
--rollback ALTER TABLE ai_article_jobs DROP COLUMN model_profile_id;
--rollback ALTER TABLE topic_discovery_runs DROP COLUMN model_profile_id;
