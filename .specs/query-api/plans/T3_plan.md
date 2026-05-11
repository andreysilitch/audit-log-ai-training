# TASK-3 Plan: `AuditEventPage` + Service Pagination Return Type

## Context

Service currently returns `List<AuditEvent>` — no way to expose `hasMore`/`nextOffset` to the API layer. Spec response envelope needs these fields. Add a domain record so pagination policy stays in the domain (AGENTS.md rule 1).

## Dependencies

- T2 (validation must run before pagination logic).

## Files

Create:
- `src/main/java/com/example/audit/domain/AuditEventPage.java`

Modify:
- `src/main/java/com/example/audit/domain/AuditEventService.java` — change return type, add page-building logic.
- `src/test/java/com/example/audit/domain/AuditEventServiceTest.java` — add pagination cases.

Note: `AuditEventRepository.search()` signature stays `List<AuditEvent>` — the contract becomes "returns up to `limit+1` rows" (actual SQL change in T4).

## Implementation

### `AuditEventPage`

```java
package com.example.audit.domain;

import java.util.List;

public record AuditEventPage(
    List<AuditEvent> items,
    int limit,
    int offset,
    Integer nextOffset,
    boolean hasMore) {}
```

`nextOffset` is `Integer` (not `int`) so it can be `null` when `hasMore == false`. Aligns with JSON contract — Jackson serialises `null` field which is acceptable per spec; alternative is `@JsonInclude(NON_NULL)` if the spec example treats `null` as omitted.

### `AuditEventService.search()` pagination body

After the validation block (T2):

```java
List<AuditEvent> rows = repository.search(c);          // up to limit+1
boolean hasMore = rows.size() > c.limit();
List<AuditEvent> items = hasMore ? List.copyOf(rows.subList(0, c.limit())) : rows;
Integer nextOffset = hasMore ? c.offset() + c.limit() : null;
return new AuditEventPage(items, c.limit(), c.offset(), nextOffset, hasMore);
```

Return type updates to `AuditEventPage`.

### Unit tests (additions)

| Case | Repo returns | Expected page |
|------|--------------|---------------|
| empty | `[]` | `items=[], hasMore=false, nextOffset=null` |
| partial page (size < limit) | 3 rows, `limit=10` | `hasMore=false, nextOffset=null` |
| exact page (size == limit) | 10 rows, `limit=10` | `hasMore=false, nextOffset=null` |
| has more (size == limit+1) | 11 rows, `limit=10`, `offset=20` | `items.size()=10, hasMore=true, nextOffset=30` |

## DoD checklist

- [ ] Record `AuditEventPage` exists with fields `items, limit, offset, nextOffset, hasMore`.
- [ ] `AuditEventService.search()` returns `AuditEventPage`.
- [ ] `nextOffset = offset + items.size()` when `hasMore`; else `null`. *(Equivalently `offset + limit` because items are trimmed to limit on `hasMore`.)*
- [ ] Unit tests verify `hasMore` and `nextOffset` calculation.

## Verification

```bash
./gradlew compileJava            # downstream compile errors flagged early (controller still using List)
./gradlew test --tests com.example.audit.domain.AuditEventServiceTest
```

Controller will fail to compile until T5 updates the caller — expected; complete T5 next.
