# TASK-4 Plan: Repository Pagination + Tie-Break Ordering

## Context

Current `PostgresAuditEventRepository.search()` (lines 98-122) orders by `timestamp DESC, sequence_no DESC` with `LIMIT ? OFFSET ?`. Spec mandates `timestamp DESC, id DESC` and fetching `limit+1` rows so the service can detect `hasMore`. `latest()` (line 125) is part of the hash-chain write path and stays on `sequence_no DESC`.

## Dependencies

- T1 (indexes exist for efficient sorted scans).
- T3 (service expects up-to-`limit+1` rows).

## Files

Modify:
- `src/main/java/com/example/audit/persistence/PostgresAuditEventRepository.java` — `search()` only.

Create (or extend existing):
- `src/integrationTest/java/com/example/audit/PostgresAuditEventRepositoryIntegrationTest.java` — add deterministic-order test case.

Do not modify: `latest()`, `findOlderThan()`, `append()`.

## Implementation

### Repository SQL change

In `search()`:

```java
sql.append(" ORDER BY timestamp DESC, id DESC LIMIT ? OFFSET ?");
args.add(c.limit() + 1);
args.add(c.offset());
```

No other changes — filters and bindings unchanged.

### Integration test

`deterministicTieBreakOnIdenticalTimestamp`:

1. Use a fixed `Clock` (or stub via `ClockConfig`) so three appended events share the same `timestamp` (truncated to micros).
2. Run `repository.search(criteria)` with `actor=...` matching all three.
3. Assert returned `id` order matches `id DESC` (sort UUIDs as strings descending).
4. Run search again; assert identical sequence (stable).

Sanity test: when 6 rows match and `limit=5`, repo returns 6 rows so the service can detect `hasMore`.

## DoD checklist

- [ ] `search()` orders by `timestamp DESC, id DESC`.
- [ ] `search()` binds `limit + 1` as the LIMIT.
- [ ] Tie-break uses `id`, not `sequence_no`.
- [ ] Integration test asserts deterministic order on identical timestamps.
- [ ] `latest()` still orders by `sequence_no DESC`.

## Verification

```bash
./gradlew integrationTest --tests com.example.audit.PostgresAuditEventRepositoryIntegrationTest
./gradlew test
```

Verify with EXPLAIN against Postgres that the new index is used:

```sql
EXPLAIN SELECT id, timestamp FROM audit_events
WHERE actor='alice' AND timestamp >= '...' AND timestamp < '...'
ORDER BY timestamp DESC, id DESC LIMIT 51 OFFSET 0;
-- expect Index Scan on idx_audit_events_actor_timestamp_id
```
