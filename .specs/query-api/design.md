# Design: Audit Events Query API

## API Contract

### Endpoint

`GET /audit-events`

### Query Parameters

| Name | Required | Type | Notes |
| --- | --- | --- | --- |
| `from` | yes | ISO-8601 UTC timestamp | Inclusive lower bound of the search window. |
| `to` | yes | ISO-8601 UTC timestamp | Exclusive upper bound of the search window. |
| `actor` | conditional | string | Optional by itself, but at least one of `actor` or `resource` must be provided. |
| `resource` | conditional | string | Optional by itself, but at least one of `actor` or `resource` must be provided. |
| `offset` | no | integer | Zero-based position of the first item to return. Default `0`. |
| `limit` | no | integer | Page size. Default `50`, maximum `200`. |

### Response Shape

The response is a page envelope rather than a bare array so the API can carry pagination metadata safely.

```json
{
  "items": [
    {
      "id": "7d6d0f3e-21b2-4d7a-9c64-1f7b9f98a2d3",
      "occurredAt": "2026-04-17T11:02:14Z",
      "actor": "u_42",
      "resource": "order/9f3b...",
      "action": "order.refunded",
      "outcome": "SUCCESS",
      "context": {
        "reason": "duplicate_charge"
      }
    }
  ],
  "page": {
    "limit": 50,
    "offset": 0,
    "nextOffset": 50,
    "hasMore": true
  }
}
```

### Contract Decisions

- The API remains read-only.
- The API returns `occurredAt` at the HTTP boundary, mapped from the persisted `timestamp` field.
- The API returns `context` rather than `payload` to align with the existing domain model and storage schema.
- An empty match returns `200 OK` with `"items": []`.

### Status Codes

| Status | When |
| --- | --- |
| `200 OK` | Search completed successfully, including empty result sets. |
| `400 Bad Request` | The request cannot be parsed, for example malformed timestamp format or a non-numeric `offset` value. |
| `401 Unauthorized` | The caller is not authenticated. |
| `403 Forbidden` | The caller is authenticated but does not have permission to read audit events. |
| `422 Unprocessable Entity` | The request is syntactically valid but violates query rules, for example missing both `actor` and `resource`, `from >= to`, blank filter values, or `limit` outside the allowed range. |
| `500 Internal Server Error` | Unexpected server-side error. The body must stay safe and not leak SQL, schema, or stack traces. |

### Error Body

```json
{
  "error": "validation_failed",
  "message": "at least one of actor or resource is required"
}
```

For field-specific validation failures, the API may add a `fields` object, but it must keep the response safe and compact.

## Sort and Determinism

### Canonical Order

The canonical order is:

`occurredAt DESC, id DESC`

At the database level this maps to:

`timestamp DESC, id DESC`

### Why This Order

- Recent-first ordering is better for the most common operational workflow: start with the latest suspicious or incident-related activity.
- The secondary `id` sort is required to make the order total and deterministic when multiple events share the same timestamp.
- Deterministic ordering is mandatory for safe pagination and for repeatable compliance review.

### Tie-Breaker Rule

If two or more events have the same `occurredAt`, they are ordered by `id DESC`.

This tie-breaker is not intended to carry business meaning. Its role is only to guarantee a stable and unique ordering for page boundaries.

## Pagination Strategy

### Chosen Strategy

Offset pagination using `offset` and `limit`.

### Pagination Semantics

- First page: client sends no `offset`, or `offset=0`.
- Next page: client sends the `nextOffset` returned by the previous page.
- The server applies the original filters and skips the first `offset` rows in canonical order.

For descending order, the repository query is:

```sql
ORDER BY timestamp DESC, id DESC
LIMIT :limitPlusOne OFFSET :offset
```

### Why Offset Pagination

- It is simple for clients to understand and debug.
- It aligns with the current repository and controller shape, reducing implementation complexity for this version.
- It remains usable for bounded audit investigations because the API requires a fixed `from/to` window and deterministic ordering.

### Stability and Trade-Offs

Offset pagination is less robust than cursor pagination when the result set changes during traversal. This design therefore makes the guarantee more specific:

- No loss and no duplication are expected when the client paginates through a stable query scope.
- The query scope is more likely to stay stable because the API requires fixed `from` and `to` bounds.
- The strongest operational pattern is to use a closed historical window where `to` is already in the past.

If clients must paginate through actively changing windows near the current time, a future revision should move to cursor pagination.

### Interaction With the Required Time Range

The request must always include `from` and `to`, and both values remain fixed across all pages of one traversal. This makes the query scope explicit and bounded, which is important for:

- repeatable investigations;
- predictable performance;
- stable pagination semantics.

### `hasMore` and `nextOffset`

- The repository fetches `limit + 1` rows.
- If more than `limit` rows are returned, the extra row is used only to compute `hasMore = true`.
- `nextOffset` is computed as `offset + number_of_returned_items`.
- If there are no more rows, `nextOffset` is `null` and `hasMore` is `false`.

## Indexes

### Required Indexes

The design standardizes these two B-tree indexes:

```sql
CREATE INDEX idx_audit_events_actor_timestamp_id
    ON audit_events (actor, timestamp DESC, id DESC);

CREATE INDEX idx_audit_events_resource_timestamp_id
    ON audit_events (resource, timestamp DESC, id DESC);
```

### Why These Indexes

- They match the required filters from the contract.
- They support the canonical sort order directly.
- They support efficient sorted scans for the required filters and deterministic ordering.
- They avoid introducing a heavier combined `(actor, resource, timestamp, id)` index before real workload data proves it is necessary.

### Query Patterns Covered

- `actor + time range`
- `resource + time range`
- `actor + resource + time range`

For combined `actor + resource` queries, PostgreSQL can use the more selective index and apply the other predicate as a filter. If production measurements later show a consistent bottleneck for combined lookups, a dedicated composite index can be introduced in a later migration.

### Migration Plan

- Add a new Flyway migration, for example `V2__add_query_api_search_indexes.sql`.
- Keep schema changes additive only.
- Do not modify `V1__create_audit_events.sql` after it has already been applied.

## Validation Rules

The controller must validate HTTP syntax, but the domain service must also enforce the query invariants so the rules are not coupled to Spring MVC.

### Request Validation

- `from` is required.
- `to` is required.
- `from` must be strictly earlier than `to`.
- At least one of `actor` or `resource` must be present.
- If provided, `actor` must not be blank.
- If provided, `resource` must not be blank.
- `limit` must be between `1` and `200`.
- `offset`, if provided, must be a non-negative integer.

### Status Mapping

- Use `400` for parse failures.
- Use `422` for semantic validation failures.

### Boundary Semantics

- `from` is inclusive.
- `to` is exclusive.

This choice avoids overlap when clients split time windows into adjacent ranges.

## Integration With API, Domain, and Infrastructure Layers

### API Layer

Planned API-layer changes:

- Keep `offset` in the `GET /audit-events` contract and remove any cursor-specific assumptions from the design.
- Replace the bare `List<AuditEventResponse>` response with a page envelope, for example `AuditEventSearchResponse`.
- Keep `AuditEventController` focused on request binding, HTTP status codes, and response serialization.
- Extend `ApiExceptionHandler` so semantic query errors can return `422`.

### Domain Layer

Planned domain-layer changes:

- Add domain validation for the new query rules:
  `from/to` required, `from < to`, at least one of `actor/resource`, limit range, non-negative offset.
- Keep the search use case in `AuditEventService` so invariants are enforced outside the controller.

Recommended domain objects:

- `AuditEventSearchCriteria`
- `AuditEventPage`

### Infrastructure Layer

Planned persistence-layer changes:

- Keep `PostgresAuditEventRepository.search(...)` on offset pagination.
- Keep filtering on `timestamp`, `actor`, and `resource`.
- Order by `timestamp DESC, id DESC`.
- Fetch `limit + 1` rows with `OFFSET` to determine whether another page exists.
- Add the new composite indexes through Flyway.

The repository interface remains append-only from a write perspective. This feature adds only read/query behavior and does not introduce any `update` or `delete` capability.

## AGENTS.md Alignment

This design follows the project rules from `AGENTS.md`.

### Read-Only and Append-Only

- The endpoint is read-only.
- No event mutation or deletion is introduced.
- Repository contracts remain free of generic `update` and `delete` methods.

### Server-Controlled Time and Investigability

- Query results expose the server-recorded event time as `occurredAt`.
- Returned events include the minimum fields required for investigation: id, occurred time, actor, resource, action, outcome, and context.

### Database-Enforced Reliability

- Query performance depends on explicit PostgreSQL indexes, not only Java-side filtering.
- Schema changes are defined through Flyway migrations only.

### Validation Outside the Controller

- Search invariants are enforced in the domain/service layer, not only in Spring MVC annotations.

### Safe API Errors

- Validation errors stay understandable for clients.
- Infrastructure details, SQL text, stack traces, and schema internals are not exposed in API responses.

### Integration Testing Expectations

The implementation should add or update Testcontainers-based integration tests for:

- `actor + time range` search;
- `resource + time range` search;
- offset pagination across multiple pages for a fixed query window;
- deterministic ordering when multiple events share the same timestamp;
- validation failures returning `400` and `422` as designed.
