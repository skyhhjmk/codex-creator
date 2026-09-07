--liquibase formatted sql

--changeset codex-creator:008-turn-progress
ALTER TABLE codex_turns
    ADD COLUMN progress JSONB NOT NULL DEFAULT '[]'::jsonb;

--rollback ALTER TABLE codex_turns DROP COLUMN progress;
