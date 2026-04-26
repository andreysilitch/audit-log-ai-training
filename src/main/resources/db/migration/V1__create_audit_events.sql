CREATE TABLE audit_events (
    id           UUID PRIMARY KEY,
    timestamp    TIMESTAMPTZ NOT NULL,
    actor        TEXT        NOT NULL,
    action       TEXT        NOT NULL,
    resource     TEXT        NOT NULL,
    outcome      TEXT        NOT NULL,
    context      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    prev_hash    TEXT,
    hash         TEXT        NOT NULL,
    sequence_no  BIGSERIAL   NOT NULL UNIQUE,
    CONSTRAINT audit_events_actor_not_blank    CHECK (length(btrim(actor))    > 0),
    CONSTRAINT audit_events_action_not_blank   CHECK (length(btrim(action))   > 0),
    CONSTRAINT audit_events_resource_not_blank CHECK (length(btrim(resource)) > 0),
    CONSTRAINT audit_events_outcome_valid      CHECK (outcome IN ('SUCCESS','DENIED','ERROR'))
);

CREATE INDEX idx_audit_events_actor              ON audit_events (actor);
CREATE INDEX idx_audit_events_resource           ON audit_events (resource);
CREATE INDEX idx_audit_events_timestamp          ON audit_events (timestamp);
CREATE INDEX idx_audit_events_actor_timestamp    ON audit_events (actor, timestamp DESC);
CREATE INDEX idx_audit_events_resource_timestamp ON audit_events (resource, timestamp DESC);

-- Append-only enforcement at DB level (invariant 1 in AGENTS.md).
CREATE OR REPLACE FUNCTION audit_events_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_events_no_update
    BEFORE UPDATE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION audit_events_immutable();

CREATE TRIGGER audit_events_no_delete
    BEFORE DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION audit_events_immutable();

-- Archive table: physical separation, written by RetentionPolicyService/ArchivalService.
CREATE TABLE audit_events_archive (
    id           UUID PRIMARY KEY,
    timestamp    TIMESTAMPTZ NOT NULL,
    actor        TEXT        NOT NULL,
    action       TEXT        NOT NULL,
    resource     TEXT        NOT NULL,
    outcome      TEXT        NOT NULL,
    context      JSONB       NOT NULL,
    prev_hash    TEXT,
    hash         TEXT        NOT NULL,
    sequence_no  BIGINT      NOT NULL,
    archived_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_events_archive_timestamp ON audit_events_archive (timestamp);
