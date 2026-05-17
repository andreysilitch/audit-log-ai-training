# TASK-2 Plan: Domain Validation Rules

## Context

Current search validation still reflects the older pagination model. The updated spec requires:

- `from` and `to` are required;
- `from < to`;
- at least one of `actor` or `resource` is required;
- blank `actor` / `resource` are rejected when provided;
- `limit` is constrained to `[1, 200]`;
- semantic validation errors use `QueryValidationException` so the API layer can return `422`.

Cursor syntax is part of the HTTP contract, so malformed cursor payloads are handled before the service executes the search. Domain validation still owns the query invariants.

## Dependencies

None. T6 reuses the exception type and messages defined here.

## Files

Create:
- `src/main/java/com/example/audit/domain/QueryValidationException.java`

Modify:
- `src/main/java/com/example/audit/domain/AuditEventService.java`
- `src/test/java/com/example/audit/domain/AuditEventServiceTest.java`

## Implementation

### `QueryValidationException`

```java
package com.example.audit.domain;

public class QueryValidationException extends RuntimeException {
  public QueryValidationException(String message) {
    super(message);
  }
}
```

### `AuditEventService.search()` validation

Use this validation order:

1. time range presence;
2. time range ordering;
3. `actor/resource` mutex;
4. blank individual filters;
5. `limit` bounds.

Representative shape:

```java
if (criteria.from() == null || criteria.to() == null) {
  throw new QueryValidationException("from and to are required");
}
if (!criteria.from().isBefore(criteria.to())) {
  throw new QueryValidationException("from must be before to");
}

boolean actorBlank = criteria.actor() == null || criteria.actor().isBlank();
boolean resourceBlank = criteria.resource() == null || criteria.resource().isBlank();
if (actorBlank && resourceBlank) {
  throw new QueryValidationException("at least one of actor or resource is required");
}
if (criteria.actor() != null && criteria.actor().isBlank()) {
  throw new QueryValidationException("actor must not be blank");
}
if (criteria.resource() != null && criteria.resource().isBlank()) {
  throw new QueryValidationException("resource must not be blank");
}
if (criteria.limit() < 1 || criteria.limit() > 200) {
  throw new QueryValidationException("limit must be between 1 and 200");
}
```

### Unit tests

Add or update cases for:

| Case | Expected message contains |
|---|---|
| `from == null` | `from and to are required` |
| `to == null` | `from and to are required` |
| `from == to` | `from must be before to` |
| `from > to` | `from must be before to` |
| both filters absent | `at least one of actor or resource` |
| both filters blank | `at least one of actor or resource` |
| blank actor with valid resource | `actor must not be blank` |
| blank resource with valid actor | `resource must not be blank` |
| `limit = 0` | `limit must be between 1 and 200` |
| `limit = 201` | `limit must be between 1 and 200` |
| valid criteria | repository search invoked once |

## DoD checklist

- [ ] Domain exception thrown for null `from` or null `to`.
- [ ] Domain exception thrown for `from >= to`.
- [ ] Domain exception thrown when both `actor` and `resource` are absent or blank.
- [ ] Domain exception thrown for blank `actor` when provided.
- [ ] Domain exception thrown for blank `resource` when provided.
- [ ] Domain exception thrown for `limit` outside `[1, 200]`.
- [ ] Unit tests cover all search validation branches.

## Verification

```bash
./gradlew test --tests com.example.audit.domain.AuditEventServiceTest
```
