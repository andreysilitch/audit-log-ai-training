# TASK-3 Plan: `AuditEventPage` and Cursor Pagination Metadata

## Context

The service must no longer expose pagination state as row offsets. The updated spec requires a page envelope carrying:

- `items`;
- `limit`;
- `cursor` for the current request, or `null` on the first page;
- `nextCursor` when another page exists, otherwise `null`;
- `hasMore`.

The repository contract can stay `List<AuditEvent>` as long as it returns up to `limit + 1` rows for continuation detection.

## Dependencies

- T2, because validation runs before page construction.

## Files

Create:
- `src/main/java/com/example/audit/domain/AuditEventCursor.java`

Modify:
- `src/main/java/com/example/audit/domain/AuditEventPage.java`
- `src/main/java/com/example/audit/domain/AuditEventSearchCriteria.java`
- `src/main/java/com/example/audit/domain/AuditEventService.java`
- `src/test/java/com/example/audit/domain/AuditEventServiceTest.java`

## Implementation

### `AuditEventCursor`

Add a domain cursor object that captures the canonical sort key:

```java
public record AuditEventCursor(Instant occurredAt, UUID id) {
  public static AuditEventCursor of(AuditEvent event) { ... }
  public String encode() { ... }
  public static AuditEventCursor decode(String encoded) { ... }
}
```

The token remains opaque at the API boundary. Internally it must round-trip `occurredAt` and `id`.

### `AuditEventPage`

```java
public record AuditEventPage(
    List<AuditEvent> items,
    int limit,
    String cursor,
    String nextCursor,
    boolean hasMore) {}
```

### `AuditEventService.search()`

Representative pagination logic:

```java
List<AuditEvent> rows = repository.search(criteria); // up to limit + 1
boolean hasMore = rows.size() > criteria.limit();
List<AuditEvent> items =
    hasMore ? List.copyOf(rows.subList(0, criteria.limit())) : List.copyOf(rows);
String nextCursor =
    hasMore ? AuditEventCursor.of(items.get(items.size() - 1)).encode() : null;
String currentCursor =
    criteria.cursor() == null ? null : criteria.cursor().encode();
return new AuditEventPage(items, criteria.limit(), currentCursor, nextCursor, hasMore);
```

## Unit tests

Add or update page-shape cases:

| Case | Repo returns | Expected |
|---|---|---|
| empty page | `[]` | `items=[]`, `hasMore=false`, `nextCursor=null` |
| partial page | 3 rows with `limit=10` | `hasMore=false`, `nextCursor=null` |
| exact page | 10 rows with `limit=10` | `hasMore=false`, `nextCursor=null` |
| continuation | 11 rows with `limit=10` | `items.size()=10`, `hasMore=true`, `nextCursor` equals last trimmed item cursor |
| echo current cursor | valid input cursor | page `cursor` matches encoded input cursor |

## DoD checklist

- [ ] `AuditEventCursor` exists and can encode/decode `occurredAt` and `id`.
- [ ] `AuditEventPage` stores `items`, `limit`, `cursor`, `nextCursor`, `hasMore`.
- [ ] `AuditEventSearchCriteria` carries the parsed cursor object rather than an offset.
- [ ] `AuditEventService.search()` returns `AuditEventPage`.
- [ ] `nextCursor` is derived from the last returned item when `hasMore` is true.
- [ ] Unit tests verify page metadata and cursor derivation.

## Verification

```bash
./gradlew compileJava
./gradlew test --tests com.example.audit.domain.AuditEventServiceTest
```
