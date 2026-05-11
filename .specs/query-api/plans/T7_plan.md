# TASK-7 Plan: Integration Tests for Query API

## Context

Cover the full search contract end-to-end on a real Postgres via Testcontainers, exercising filters, pagination, deterministic ordering, and the 400/422 split. Pattern matches existing `AuditEventControllerIntegrationTest` and `PostgresAuditEventRepositoryIntegrationTest` (Testcontainers + `@ServiceConnection` + `postgres:16-alpine`).

## Dependencies

- T1–T6 (full implementation in place).

## Files

Create:
- `src/integrationTest/java/com/example/audit/AuditEventQueryApiIntegrationTest.java`

May also touch:
- `src/integrationTest/java/com/example/audit/PostgresAuditEventRepositoryIntegrationTest.java` (already covers the tie-break case from T4 — not duplicated here).

## Test class skeleton

```java
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuditEventQueryApiIntegrationTest {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired MockMvc mvc;
  @Autowired AuditEventService service;   // for seeding via domain (server-controlled timestamp)
  @Autowired Clock clock;                 // configurable via ClockConfig for deterministic seeding
  @Autowired ObjectMapper objectMapper;
}
```

Use `service.record(...)` to seed (respects append-only + hash chain). For timestamp control, replace `ClockConfig`'s `Clock` bean with a mutable test clock (`Clock.fixed` advanced via helper) so multiple events can share an instant.

## Scenarios (DoD-aligned)

| # | Test name | Setup | Assertion |
|---|-----------|-------|-----------|
| 1 | `searchesByActorAndTimeRange` | seed 3 events for `alice`, 2 for `bob`, all in window | `actor=alice&from&to` returns 3 `alice` events, none `bob` |
| 2 | `searchesByResourceAndTimeRange` | seed events for `order/9` and `order/1` | `resource=order/9&from&to` returns only matching resource |
| 3 | `combinesActorAndResource` | seed mixed | `actor=alice&resource=order/9&from&to` AND-filters |
| 4 | `paginatesAcrossPages` | seed 7 matching events | loop `limit=3` using `nextOffset`; assert 3+3+1 with no loss/dup; final page `hasMore=false, nextOffset=null` |
| 5 | `deterministicOrderOnIdenticalTimestamp` | with fixed clock seed 3 events at same instant | order is `id DESC`; stable across two calls |
| 6 | `emptyResultReturnsOkWithEmptyItems` | empty window | 200, `$.items=[]`, `$.page.hasMore=false` |
| 7 | `missingActorAndResourceReturns422` | only `from`/`to` set | 422, `$.error=validation_failed`, `$.message` mentions actor/resource |
| 8 | `fromAfterToReturns422` | `from > to` | 422 |
| 9 | `malformedTimestampReturns400` | `from=garbage` | 400 (parse) |
| 10 | `limitOutOfRangeReturns422` | `limit=0`, `limit=201` (two requests) | 422 each |
| 11 | `negativeOffsetReturns422` | `offset=-1` | 422 |
| 12 | `defaultLimitIs50` | seed >50 events; query with no `limit` | `$.page.limit=50`, `$.items.length()=50` |
| 13 | `noHashOrSequenceNoLeaked` | seed one, search | response items have `occurredAt` but no `hash` / `sequenceNo` keys |

## Seeding helper sketch

```java
private void seed(String actor, String resource, String action, Instant at) {
  // Use a TestClock bean to drive timestamps deterministically.
  testClock.setNow(at);
  service.record(actor, action, resource, AuditOutcome.SUCCESS, NullNode.getInstance());
}
```

If introducing a `TestClock` is too heavy, fall back to sleeping between appends and querying with a generous `[from, to)` window — but the tie-break scenario (#5) requires identical timestamps and cannot rely on wall-clock.

## DoD checklist (from tasks.md)

- [ ] actor + time range search.
- [ ] resource + time range search.
- [ ] actor + resource + time range search.
- [ ] Offset pagination across multiple pages, no loss/dup.
- [ ] Deterministic order on identical timestamps.
- [ ] Empty result → 200 with `"items": []`.
- [ ] Missing both actor and resource → 422.
- [ ] `from >= to` → 422.
- [ ] Malformed timestamp → 400.
- [ ] `limit` outside `[1, 200]` → 422.
- [ ] All tests use Testcontainers + PostgreSQL.

## Verification

```bash
./gradlew integrationTest
./gradlew build      # full pipeline including arch tests
```

Watch for: flaky test #4 if seeding races the query window — use a far-past `from` and far-future `to` derived from the fixed clock.
