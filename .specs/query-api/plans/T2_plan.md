# TASK-2 Plan: Domain Validation Rules

## Context

Current `AuditEventService.search()` (lines 39-53) validates `from/to` non-null, `from < to`, `1 <= limit <= 1000`, `offset >= 0`, all via `IllegalArgumentException`. Spec requires stricter rules (limit max 200, actor/resource mutex, no blank values) and a distinguishable exception so the API layer can return 422. AGENTS.md rule 1: validation lives in domain, not the controller.

## Dependencies

None. (T6 reuses the exception type created here.)

## Files

Create:
- `src/main/java/com/example/audit/domain/QueryValidationException.java`
- `src/test/java/com/example/audit/domain/AuditEventServiceTest.java`

Modify:
- `src/main/java/com/example/audit/domain/AuditEventService.java` — `search()` validation block only.

## Implementation

### `QueryValidationException`

```java
package com.example.audit.domain;

public class QueryValidationException extends RuntimeException {
  public QueryValidationException(String message) { super(message); }
}
```

### `AuditEventService.search()` validation

Replace existing validation block:

```java
public List<AuditEvent> search(AuditEventSearchCriteria c) {  // return type changes in T3
  if (c.from() == null || c.to() == null)
      throw new QueryValidationException("from and to are required");
  if (!c.from().isBefore(c.to()))
      throw new QueryValidationException("from must be before to");

  boolean actorBlank    = c.actor()    == null || c.actor().isBlank();
  boolean resourceBlank = c.resource() == null || c.resource().isBlank();
  if (actorBlank && resourceBlank)
      throw new QueryValidationException("at least one of actor or resource is required");
  if (c.actor() != null && c.actor().isBlank())
      throw new QueryValidationException("actor must not be blank");
  if (c.resource() != null && c.resource().isBlank())
      throw new QueryValidationException("resource must not be blank");

  if (c.limit() < 1 || c.limit() > 200)
      throw new QueryValidationException("limit must be between 1 and 200");
  if (c.offset() < 0)
      throw new QueryValidationException("offset must be non-negative");

  return repository.search(c);
}
```

Validation order: time range → mutex → blank → numeric bounds. This gives the most useful first message when several fields are bad.

### Unit tests

JUnit 5 + Mockito. Repository mocked. Parameterised cases:

| Case | Criteria | Expected message contains |
|------|----------|---------------------------|
| from null | `(actor=a, from=null, to=t1)` | `from and to` |
| to null | `(actor=a, from=t0, to=null)` | `from and to` |
| from == to | equal instants | `from must be before to` |
| from > to | reversed | `from must be before to` |
| both filters absent | `actor=null, resource=null` | `at least one of actor or resource` |
| both filters blank | `actor="", resource="  "` | `at least one of actor or resource` |
| actor blank | `actor=" ", resource="r"` | `actor must not be blank` |
| resource blank | `actor="a", resource=""` | `resource must not be blank` |
| limit 0 | `limit=0` | `limit must be between 1 and 200` |
| limit 201 | `limit=201` | `limit must be between 1 and 200` |
| offset -1 | `offset=-1` | `offset must be non-negative` |
| happy path | valid criteria | repository.search called once |

## DoD checklist

- [ ] Domain exception thrown for null `from` or null `to`.
- [ ] Domain exception thrown for `from >= to`.
- [ ] Domain exception thrown when both `actor` and `resource` absent/blank.
- [ ] Domain exception thrown for blank `actor` when provided.
- [ ] Domain exception thrown for blank `resource` when provided.
- [ ] Domain exception thrown for `limit` outside `[1, 200]`.
- [ ] Domain exception thrown for negative `offset`.
- [ ] Unit tests cover all cases.

## Verification

```bash
./gradlew test --tests com.example.audit.domain.AuditEventServiceTest
```

All cases green; existing tests unaffected (controller integration test still passes because parse-path is untouched, but its bare-list assertion is updated in T5).
