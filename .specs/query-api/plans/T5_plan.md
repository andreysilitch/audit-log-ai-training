# TASK-5 Plan: API Response Envelope and Cursor Contract

## Context

The query API must expose cursor-based pagination through the HTTP contract. Search responses use a dedicated DTO and must not leak write-path fields such as `hash` or `sequenceNo`.

The search read path differs from POST:

- POST keeps `AuditEventResponse`;
- GET uses `AuditEventSearchResponse`;
- response item timestamp is named `occurredAt`;
- page metadata uses `cursor` and `nextCursor`.

## Dependencies

- T3 for `AuditEventPage`.
- T4 for repository continuation behavior.

## Files

Modify:
- `src/main/java/com/example/audit/api/AuditEventController.java`
- `src/main/java/com/example/audit/api/AuditEventSearchRequest.java`
- `src/main/java/com/example/audit/api/AuditEventSearchResponse.java`
- `src/integrationTest/java/com/example/audit/AuditEventControllerIntegrationTest.java`

Do not modify:
- `src/main/java/com/example/audit/api/AuditEventResponse.java`

## Implementation

### Controller contract

`GET /audit-events` accepts:

- `actor`
- `resource`
- `from`
- `to`
- optional `limit`
- optional `cursor`

`limit` defaults to `50`. `cursor` is omitted on the first page.

The controller decodes `cursor` before constructing `AuditEventSearchCriteria`. Malformed cursor payloads must fail fast as safe `400` errors.

### `AuditEventSearchResponse`

Representative shape:

```java
public record AuditEventSearchResponse(List<Item> items, Page page) {

  public record Item(
      UUID id,
      Instant occurredAt,
      String actor,
      String action,
      String resource,
      String outcome,
      JsonNode context) { ... }

  public record Page(int limit, String cursor, String nextCursor, boolean hasMore) {}
}
```

### Controller integration test updates

Update search assertions to check:

- `$.items[0].occurredAt` exists;
- `$.items[0].hash` does not exist;
- `$.items[0].sequenceNo` does not exist;
- `$.page.limit == 50`;
- `$.page.cursor == null` on the first page;
- `$.page.nextCursor == null` when no more rows exist.

## DoD checklist

- [ ] Search endpoint accepts `cursor` instead of `offset`.
- [ ] `AuditEventSearchResponse` exposes `items` and `page`.
- [ ] Response uses `occurredAt`, not `timestamp`, on search items.
- [ ] Search response omits `hash`, `sequenceNo`, and `prevHash`.
- [ ] Default `limit` is `50`.
- [ ] First page works when `cursor` is omitted.
- [ ] Integration test verifies the envelope shape.

## Verification

```bash
./gradlew integrationTest --tests com.example.audit.AuditEventControllerIntegrationTest
./gradlew test
```
