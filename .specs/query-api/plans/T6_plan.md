# TASK-6 Plan: 400 vs 422 Error Handling

## Context

`ApiExceptionHandler` currently maps `IllegalArgumentException` to 400 and treats all validation failures as parse errors. Spec separates the two: parse failures (malformed timestamp, non-numeric offset, missing required parameter) stay 400; semantic validation failures (`from >= to`, missing actor+resource, blank filter, limit out of range) become 422. Error body: `{"error": "validation_failed", "message": "..."}`.

## Dependencies

- T2 (`QueryValidationException` exists and is thrown by domain).

## Files

Modify:
- `src/main/java/com/example/audit/api/ApiExceptionHandler.java`.

## Implementation

Add a handler; leave the rest untouched.

```java
@ExceptionHandler(QueryValidationException.class)
public ResponseEntity<Map<String, Object>> handleQueryValidation(QueryValidationException ex) {
  return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
      .body(Map.of("error", "validation_failed", "message", ex.getMessage()));
}
```

Import `com.example.audit.domain.QueryValidationException`.

### Decision: keep `IllegalArgumentException → 400`

Existing write-path validation (`AuditEventService.record()`) uses `IllegalArgumentException`. Remapping it would broaden scope beyond the spec and could break POST tests. Only the new search-path semantic errors use `QueryValidationException → 422`.

### Decision: parse errors stay 400

Existing handlers cover the parse path:

| Exception | Status | Source |
|-----------|--------|--------|
| `MissingServletRequestParameterException` | 400 | missing `from`/`to` |
| `MethodArgumentTypeMismatchException` | 400 | malformed `from`/`to`/`offset` |
| `HttpMessageNotReadableException` | 400 | malformed JSON body (POST) |
| `MethodArgumentNotValidException` | 400 | bean validation (POST) |
| `IllegalArgumentException` | 400 | legacy domain errors (write path) |
| `QueryValidationException` | **422** | new — semantic search errors |
| `Exception` | 500 | catch-all |

## DoD checklist

- [ ] `QueryValidationException` exists (from T2) and is thrown by domain.
- [ ] Handler maps `QueryValidationException` → 422.
- [ ] Parse errors (malformed timestamp, non-numeric offset) → 400.
- [ ] Error body: `{"error": "validation_failed", "message": "..."}`.
- [ ] Integration tests verify 400 vs 422 split (in T7).

## Verification

```bash
./gradlew test
./gradlew integrationTest
```

Quick manual checks once running:

```bash
# Parse error → 400
curl -i 'http://localhost:8080/audit-events?from=not-a-date&to=2026-01-01T00:00:00Z&actor=alice'

# Semantic error → 422
curl -i 'http://localhost:8080/audit-events?from=2026-02-01T00:00:00Z&to=2026-01-01T00:00:00Z&actor=alice'
```
