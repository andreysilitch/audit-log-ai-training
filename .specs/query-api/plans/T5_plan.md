# TASK-5 Plan: API Response Envelope

## Context

`GET /audit-events` currently returns a bare `List<AuditEventResponse>` with `hash` and `sequenceNo` leaking on the read path. Spec mandates a paginated envelope (`items` + `page`), renames `timestamp` → `occurredAt`, and omits internal fields. POST response keeps existing `AuditEventResponse` (tests assert `$.hash`) — search needs a new DTO.

## Dependencies

- T3 (service returns `AuditEventPage`).
- T4 (repository populates pagination metadata).

## Files

Create:
- `src/main/java/com/example/audit/api/AuditEventSearchResponse.java`

Modify:
- `src/main/java/com/example/audit/api/AuditEventController.java` — `search()` defaults and return type.
- `src/integrationTest/java/com/example/audit/AuditEventControllerIntegrationTest.java` — update bare-list JSON path assertion to envelope.

Do not modify: `AuditEventResponse` (POST contract).

## Implementation

### `AuditEventSearchResponse`

```java
package com.example.audit.api;

import com.example.audit.domain.AuditEvent;
import com.example.audit.domain.AuditEventPage;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AuditEventSearchResponse(List<Item> items, Page page) {

  public record Item(
      UUID id,
      Instant occurredAt,
      String actor,
      String action,
      String resource,
      String outcome,
      JsonNode context) {

    public static Item of(AuditEvent e) {
      return new Item(
          e.id(),
          e.timestamp(),
          e.actor(),
          e.action(),
          e.resource(),
          e.outcome().name(),
          e.context());
    }
  }

  public record Page(int limit, int offset, Integer nextOffset, boolean hasMore) {}

  public static AuditEventSearchResponse of(AuditEventPage p) {
    return new AuditEventSearchResponse(
        p.items().stream().map(Item::of).toList(),
        new Page(p.limit(), p.offset(), p.nextOffset(), p.hasMore()));
  }
}
```

`hash` and `sequenceNo` deliberately absent.

### `AuditEventController.search()`

```java
private static final int DEFAULT_LIMIT  = 50;
private static final int DEFAULT_OFFSET = 0;

@GetMapping
public AuditEventSearchResponse search(
    @RequestParam(required = false) String actor,
    @RequestParam(required = false) String resource,
    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
    @RequestParam(required = false) Integer limit,
    @RequestParam(required = false) Integer offset) {
  int effectiveLimit  = limit  == null ? DEFAULT_LIMIT  : limit;
  int effectiveOffset = offset == null ? DEFAULT_OFFSET : offset;
  var criteria = new AuditEventSearchCriteria(
      actor, resource, from, to, effectiveLimit, effectiveOffset);
  return AuditEventSearchResponse.of(service.search(criteria));
}
```

Removed clamp helpers — domain layer rejects out-of-range values with 422.

### Existing integration test update

`AuditEventControllerIntegrationTest.postsAndSearchesEvents` (around line 55):

```java
mvc.perform(get("/audit-events")
        .param("actor", "alice")
        .param("from", from.toString())
        .param("to", to.toString()))
   .andExpect(status().isOk())
   .andExpect(jsonPath("$.items[0].actor").value("alice"))
   .andExpect(jsonPath("$.items[0].occurredAt").exists())
   .andExpect(jsonPath("$.items[0].hash").doesNotExist())
   .andExpect(jsonPath("$.items[0].sequenceNo").doesNotExist())
   .andExpect(jsonPath("$.page.limit").value(50))
   .andExpect(jsonPath("$.page.offset").value(0))
   .andExpect(jsonPath("$.page.hasMore").value(false));
```

`searchRequiresTimeRange` keeps `isBadRequest()` (missing required `@RequestParam` → 400 via existing handler).

## DoD checklist

- [ ] DTO `AuditEventSearchResponse` with `items` and nested `page`.
- [ ] Controller returns `AuditEventSearchResponse`.
- [ ] Response field `occurredAt` (not `timestamp`).
- [ ] Default `limit=50`, max `200` (enforced by domain).
- [ ] Default `offset=0`.
- [ ] Empty result returns `200` with `"items": []`.
- [ ] Integration test verifies envelope shape.

## Verification

```bash
./gradlew test
./gradlew integrationTest --tests com.example.audit.AuditEventControllerIntegrationTest
```

Manual smoke once T1–T6 are in place: `curl 'http://localhost:8080/audit-events?actor=alice&from=2026-01-01T00:00:00Z&to=2026-12-31T23:59:59Z'` — expect envelope shape, no `hash`/`sequenceNo` fields.
