# TASK-6 Plan: 400 vs 422 Error Handling

## Context

The spec splits failures into two classes:

- parse / binding errors stay `400`;
- semantic query validation errors become `422`.

With cursor pagination, malformed cursor payloads belong to the parse path.

## Dependencies

- T2, because semantic query validation uses `QueryValidationException`.
- T5, because the controller now parses cursor values explicitly.

## Files

Modify:
- `src/main/java/com/example/audit/api/ApiExceptionHandler.java`

## Implementation

### Semantic search validation

Keep:

```java
@ExceptionHandler(QueryValidationException.class)
ResponseEntity<Map<String, Object>> ...
```

mapping to:

```json
{"error":"validation_failed","message":"..."}
```

with HTTP `422`.

### Parse-path behavior

Keep existing `400` behavior for:

- missing `from` / `to`;
- malformed `from` / `to`;
- malformed JSON on POST;
- malformed cursor payload;
- any legacy `IllegalArgumentException` raised on the write path.

The simplest cursor-path implementation is to let cursor decoding throw `IllegalArgumentException("cursor has invalid format")`, which is already safe to map to `400`.

## DoD checklist

- [ ] `QueryValidationException` maps to `422`.
- [ ] Missing required request params stay `400`.
- [ ] Malformed timestamps stay `400`.
- [ ] Malformed cursor stays `400`.
- [ ] Write-path `IllegalArgumentException` behavior stays unchanged.
- [ ] Search semantic validation remains `422`.

## Verification

```bash
./gradlew integrationTest --tests com.example.audit.AuditEventQueryApiIntegrationTest
./gradlew test
```
