# TASK-1 Plan: Flyway V2 Migration for Query API Indexes

## Context

Spec `.specs/query-api/design.md#Indexes` requires composite B-tree indexes matching canonical search order `(timestamp DESC, id DESC)` filtered by `actor` or `resource`. V1 already has `(actor, timestamp DESC)` and `(resource, timestamp DESC)` but no `id` tie-break in the index. New migration is additive; V1 stays frozen (AGENTS.md rule 4).

## Dependencies

None.

## Files

Create:
- `src/main/resources/db/migration/V2__add_query_api_search_indexes.sql`

Do not modify:
- `src/main/resources/db/migration/V1__create_audit_events.sql`

## Implementation

```sql
CREATE INDEX IF NOT EXISTS idx_audit_events_actor_timestamp_id
    ON audit_events (actor, timestamp DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_audit_events_resource_timestamp_id
    ON audit_events (resource, timestamp DESC, id DESC);
```

Leave existing V1 indexes in place — pruning is out of scope.

## DoD checklist

- [ ] File `V2__add_query_api_search_indexes.sql` exists in `src/main/resources/db/migration`.
- [ ] Index `idx_audit_events_actor_timestamp_id` on `(actor, timestamp DESC, id DESC)`.
- [ ] Index `idx_audit_events_resource_timestamp_id` on `(resource, timestamp DESC, id DESC)`.
- [ ] Migration applies cleanly on fresh DB.
- [ ] V1 unchanged.

## Verification

```bash
./gradlew flywayInfo       # V2 listed as Pending → Success on next run
./gradlew flywayMigrate    # or start app; both apply migration
```

Manual check in psql:

```sql
\d+ audit_events
-- confirm idx_audit_events_actor_timestamp_id and idx_audit_events_resource_timestamp_id present
```
