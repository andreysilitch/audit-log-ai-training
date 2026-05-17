# TASK-4 Plan: Repository Keyset Pagination and Tie-Break Ordering

## Context

The current repository search path still reflects offset pagination assumptions. The updated spec requires keyset pagination over the canonical order:

- `ORDER BY timestamp DESC, id DESC`;
- when a cursor is present, continue with
  `timestamp < cursorTimestamp OR (timestamp = cursorTimestamp AND id < cursorId)`;
- fetch `limit + 1` rows so the service can compute `hasMore` and `nextCursor`.

`latest()` remains on `sequence_no DESC` because it belongs to the hash-chain write path.

## Dependencies

- T1 for supporting indexes.
- T3 because the service consumes up-to-`limit+1` rows and emits cursor metadata.

## Files

Modify:
- `src/main/java/com/example/audit/persistence/PostgresAuditEventRepository.java`
- `src/integrationTest/java/com/example/audit/PostgresAuditEventRepositoryIntegrationTest.java`

Do not modify:
- `latest()`
- `findOlderThan()`
- `append()`

## Implementation

### Repository SQL shape

In `search()`:

```java
if (criteria.cursor() != null) {
  sql.append(" AND (timestamp < ? OR (timestamp = ? AND id < ?))");
  args.add(Timestamp.from(criteria.cursor().occurredAt()));
  args.add(Timestamp.from(criteria.cursor().occurredAt()));
  args.add(criteria.cursor().id());
}
sql.append(" ORDER BY timestamp DESC, id DESC LIMIT ?");
args.add(criteria.limit() + 1);
```

The half-open time window remains:

```sql
timestamp >= :from AND timestamp < :to
```

### Integration tests

Keep the tie-break test and add keyset traversal assertions:

1. first query with no cursor returns `limit + 1` rows when overflow exists;
2. derive the next cursor from the last trimmed item;
3. second query with that cursor continues without overlap;
4. repeat traversal and confirm stable ordering.

## DoD checklist

- [ ] `search()` orders by `timestamp DESC, id DESC`.
- [ ] `search()` applies the keyset predicate when a cursor is present.
- [ ] `search()` binds `limit + 1` as the SQL limit.
- [ ] Tie-break uses `id`, not `sequence_no`.
- [ ] Integration test verifies deterministic order for identical timestamps.
- [ ] Integration test verifies cursor-based continuation across pages.
- [ ] `latest()` still orders by `sequence_no DESC`.

## Verification

```bash
./gradlew integrationTest --tests com.example.audit.PostgresAuditEventRepositoryIntegrationTest
```
