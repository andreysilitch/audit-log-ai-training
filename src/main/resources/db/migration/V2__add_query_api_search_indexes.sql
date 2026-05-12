-- Composite indexes for the audit events query API.
-- Match the canonical search order (timestamp DESC, id DESC) filtered by actor or resource.
-- IF NOT EXISTS keeps the migration safe to re-run during local development.
-- The pre-existing V1 indexes (actor, timestamp DESC) and (resource, timestamp DESC) are
-- intentionally left in place; pruning them is deferred until production query plans confirm
-- the new indexes are exclusively used.

CREATE INDEX IF NOT EXISTS idx_audit_events_actor_timestamp_id
    ON audit_events (actor, timestamp DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_audit_events_resource_timestamp_id
    ON audit_events (resource, timestamp DESC, id DESC);
