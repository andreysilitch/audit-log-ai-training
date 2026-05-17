# TASK-7 Plan: Integration Tests for Cursor-Based Query API

## Context

The end-to-end query contract must be verified on PostgreSQL via Testcontainers. The updated spec changes pagination behavior, so integration coverage must exercise:

- filters by actor and resource;
- deterministic ordering;
- cursor-based continuation;
- safe error handling for malformed cursor and semantic validation failures.

## Dependencies

- T1 through T6.

## Files

Modify or create:
- `src/integrationTest/java/com/example/audit/AuditEventQueryApiIntegrationTest.java`
- `src/integrationTest/java/com/example/audit/PostgresAuditEventRepositoryIntegrationTest.java`

## Test strategy

Use domain writes for seeding so the append-only and server-controlled timestamp rules stay intact.

Keep a mutable `TestClock` so:

- multiple events can share the same timestamp for tie-break assertions;
- time windows stay deterministic in pagination tests.

## Scenarios

| # | Test | Assertion |
|---|---|---|
| 1 | actor + time range | only matching actor is returned |
| 2 | resource + time range | only matching resource is returned |
| 3 | actor + resource + time range | filters combine with AND semantics |
| 4 | cursor pagination across pages | loop with `nextCursor` until `hasMore=false`; no loss or duplication |
| 5 | deterministic tie-break | identical timestamps sort by `id DESC`; repeated query yields same order |
| 6 | empty result | `200`, `items=[]`, `hasMore=false`, `nextCursor=null` |
| 7 | missing actor/resource | `422` |
| 8 | `from >= to` | `422` |
| 9 | malformed timestamp | `400` |
| 10 | malformed cursor | `400` |
| 11 | `limit=0` or `limit=201` | `422` |
| 12 | default limit | page reports `limit=50` |
| 13 | read response leakage | no `hash`, `sequenceNo`, `prevHash`, or `timestamp` keys on search items |

## DoD checklist

- [ ] actor + time range search covered.
- [ ] resource + time range search covered.
- [ ] actor + resource + time range search covered.
- [ ] cursor-based pagination covered with no loss or duplication.
- [ ] deterministic tie-break covered for identical timestamps.
- [ ] empty search result covered.
- [ ] malformed cursor covered as `400`.
- [ ] semantic validation failures covered as `422`.
- [ ] all tests run against PostgreSQL via Testcontainers.

## Verification

```bash
./gradlew integrationTest
./gradlew build
```
