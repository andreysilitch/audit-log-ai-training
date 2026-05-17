# Design: Audit Events Query API

## API Contract

### Endpoint

`GET /audit-events`

### Read-Only Audit Events Endpoint

The query API exposes only `GET /audit-events` for this feature. No write capability is introduced on the read path.

### Query Parameters

| Name | Required | Type | Notes |
| --- | --- | --- | --- |
| `from` | yes | ISO-8601 UTC timestamp | Inclusive lower bound of the search window. |
| `to` | yes | ISO-8601 UTC timestamp | Exclusive upper bound of the search window. |
| `actor` | conditional | string | Optional by itself, but at least one of `actor` or `resource` must be provided. Accepts one actor value or a comma-separated list of up to 10 actor values. |
| `resource` | conditional | string | Optional by itself, but at least one of `actor` or `resource` must be provided. |
| `cursor` | no | string | Opaque continuation token for keyset pagination. Omit for the first page. |
| `limit` | no | integer | Page size. Default `50`, maximum `200`. |

### Actor Filtering

The API accepts `actor` as an optional filter parameter and applies it exactly as part of the search criteria.

### Single-Actor and Multi-Actor Filter Semantics

- A request may supply one actor value, for example `actor=u_42`, or a comma-separated list, for example `actor=u_42,svc_billing,svc_auth`.
- The parsed actor list uses OR semantics inside the actor filter, equivalent to `actor IN (...)`.
- The actor filter still combines with `resource`, `from`, and `to` using AND semantics.
- The request may contain at most 10 actor values.

### Resource Filtering

The API accepts `resource` as an optional filter parameter and applies it exactly as part of the search criteria.

### UTC Time Range Filtering

The API requires `from` and `to` and interprets both values as UTC timestamps for the search window.

### Combining Resource with Time Range

The API supports combining `resource` with `from` and `to` inside the same query so incident reconstruction stays scoped to one resource and one time window.

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
    "cursor": null,
    "nextCursor": "eyJvY2N1cnJlZEF0IjoiMjAyNi0wNC0xN1QxMTowMjoxNFoiLCJpZCI6IjdkNmQwZjNlLTIxYjItNGQ3YS05YzY0LTFmN2I5Zjk4YTJkMyJ9",
    "hasMore": true
  }
}
```

### Event ID Timestamp Actor Resource Action Outcome and Context Fields

Each response item includes `id`, `occurredAt`, `actor`, `resource`, `action`, `outcome`, and `context`.

### Server-Recorded Event Timestamps

The response maps `occurredAt` from the persisted server-recorded `timestamp` field and never from a client-provided timestamp.

### Empty Result Set Instead of Error

When no events match the query, the API returns `200 OK` with `"items": []` and page metadata rather than a domain error.

### Contract Decisions

- The API remains read-only.
- The API returns `occurredAt` at the HTTP boundary, mapped from the persisted `timestamp` field.
- The API returns `context` rather than `payload` to align with the existing domain model and storage schema.
- An empty match returns `200 OK` with `"items": []`.
- Internal write-path fields `hash` and `sequenceNo` are never returned on the read path. They are part of the append/hash-chain contract only, and the POST response DTO that exposes them is not affected by this feature.

### Field Types

| Field | JSON type | Notes |
| --- | --- | --- |
| `id` | string (UUID) | Server-assigned event id. |
| `occurredAt` | string (ISO-8601 UTC) | Server-recorded event time. |
| `actor` | string | Non-blank. |
| `resource` | string | Non-blank. |
| `action` | string | Free-form action name. |
| `outcome` | string | Enum name, e.g. `"SUCCESS"`, `"FAILURE"`. Serialized via the enum's `name()`. |
| `context` | JSON object | Free-form JSON; mapped to `JsonNode` in Java. May be empty. |
| `page.limit` | integer | Echoes the effective `limit`. |
| `page.cursor` | string or `null` | Echoes the cursor supplied by the client, or `null` on the first page. |
| `page.nextCursor` | string or `null` | `null` when `hasMore` is `false`. The field is always present so clients can rely on its key. |
| `page.hasMore` | boolean | Whether more results exist beyond this page. |

### Status Codes

| Status | When |
| --- | --- |
| `200 OK` | Search completed successfully, including empty result sets. |
| `400 Bad Request` | The request cannot be parsed, for example malformed timestamp format, malformed cursor, or a non-numeric `limit` value. |
| `401 Unauthorized` | The caller is not authenticated. |
| `403 Forbidden` | The caller is authenticated but does not have permission to read audit events. |
| `422 Unprocessable Entity` | The request is syntactically valid but violates query rules, for example missing both `actor` and `resource`, `from >= to`, blank filter values, an `actor` list with more than 10 values, or `limit` outside the allowed range. |
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

### Deterministic Result Order for Repeated Requests

The API uses one canonical order for repeated requests over the same dataset so clients can review the same sequence consistently.

### Deterministic Chronological Order for Timeline Reconstruction

The descending event-time order supports timeline reconstruction by presenting the latest matching events first while keeping a stable chronology.

### Why This Order

- Recent-first ordering is better for the most common operational workflow: start with the latest suspicious or incident-related activity.
- The secondary `id` sort is required to make the order total and deterministic when multiple events share the same timestamp.
- Deterministic ordering is mandatory for safe pagination and for repeatable compliance review.

### Deterministic Tie-Break Strategy

Events with identical timestamps are disambiguated by `id DESC` so page boundaries and repeated reads remain stable.

### Tie-Breaker Rule

If two or more events have the same `occurredAt`, they are ordered by `id DESC`.

This tie-breaker is not intended to carry business meaning. Its role is only to guarantee a stable and unique ordering for page boundaries.

## Pagination Strategy

### Chosen Strategy

Cursor-based pagination using `cursor` and `limit`.

### Cursor and Limit Pagination

The pagination contract is expressed only through `cursor` and `limit`; the API does not expose row offsets.

### Cursor Format

- The cursor is opaque at the HTTP contract level.
- Internally it encodes the canonical sort key of the last item returned on the previous page: `occurredAt` and `id`.
- The server may serialize that payload as Base64URL JSON or an equivalent stable encoding.
- Clients must treat the cursor as an opaque token and must not construct or modify it manually.

### Pagination Semantics

- First page: client omits `cursor`.
- Next page: client sends the `nextCursor` returned by the previous page.
- The server applies the original filters and adds a keyset predicate based on the canonical sort order encoded in the cursor.

### Stable Pagination Across Query Scope

Clients must keep `actor`, `resource`, `from`, `to`, and `limit` fixed while following returned cursor values so the traversal remains within one deterministic query scope.

For a multi-actor request, the full parsed actor list is part of that fixed query scope. Cursor traversal is valid only when the same actor set, resource filter, time bounds, and limit are reused across page requests.

For descending order, the repository query is:

```sql
WHERE (
    timestamp < :cursorTimestamp
    OR (timestamp = :cursorTimestamp AND id < :cursorId)
)
ORDER BY timestamp DESC, id DESC
LIMIT :limitPlusOne
```

The keyset predicate is only applied when a cursor is present. The first page uses the same filters and sort order without that extra predicate.

### Why Cursor Pagination

- It preserves stable traversal under deterministic ordering without row skipping caused by shifting row positions.
- It aligns with the requirement for stable pagination over large audit investigations.
- It keeps the contract explicit while avoiding exposure of internal row positions.

### Stability and Trade-Offs

- No loss and no duplication are expected when the client paginates through a stable query scope.
- The query scope is more likely to stay stable because the API requires fixed `from` and `to` bounds.
- The strongest operational pattern is to use a closed historical window where `to` is already in the past.
- Even with cursor pagination, clients should keep `from`, `to`, `actor`, and `resource` fixed across page requests; changing filters invalidates the traversal.

### Interaction With the Required Time Range

The request must always include `from` and `to`, and both values remain fixed across all pages of one traversal. This makes the query scope explicit and bounded, which is important for:

- repeatable investigations;
- predictable performance;
- stable pagination semantics.

### `hasMore` and `nextCursor`

- The repository fetches `limit + 1` rows.
- If more than `limit` rows are returned, the extra row is trimmed and used only to compute `hasMore = true`. The response `items` array contains at most `limit` entries.
- `nextCursor` is derived from the last item in the trimmed `items` array when `hasMore` is `true`.
- If there are no more rows, `nextCursor` is `null` and `hasMore` is `false`. The `nextCursor` key is still serialized as `null` so the response shape is stable.

### At-Most Limit Events Per Page

Each page contains no more than the effective `limit`, even when the repository reads one extra row internally to detect continuation.

### nextCursor Continuation Value and hasMore Signaling

When additional rows exist after trimming, the response returns `nextCursor` and `hasMore = true`; otherwise it returns `nextCursor = null` and `hasMore = false`.

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

- `multi-actor + time range`
- `actor + time range`
- `resource + time range`
- `multi-actor + resource + time range`
- `actor + resource + time range`

For combined `actor + resource` queries, PostgreSQL can use the more selective index and apply the other predicate as a filter. If production measurements later show a consistent bottleneck for combined lookups, a dedicated composite index can be introduced in a later migration.

### Multi-Actor Index Strategy

The existing `idx_audit_events_actor_timestamp_id` index is sufficient for the multi-actor filter introduced in the requirements.

- The actor list is capped at 10 values, which bounds the number of actor-specific index probes in one query.
- PostgreSQL can satisfy `actor IN (...)` by combining index access paths for the existing `(actor, timestamp DESC, id DESC)` index and still preserve efficient filtering on the bounded time window.
- Because the canonical sort order is already covered by that index, the multi-actor feature does not require a new composite index in this version.
- A new index is therefore not introduced unless production query plans show that the bounded IN-list pattern regresses materially.

### Migration Plan

- Add a new Flyway migration, for example `V2__add_query_api_search_indexes.sql`.
- Keep schema changes additive only.
- Do not modify `V1__create_audit_events.sql` after it has already been applied.
- Use `CREATE INDEX IF NOT EXISTS` so the migration is safe to re-run during local development.
- The pre-existing V1 indexes `(actor, timestamp DESC)` and `(resource, timestamp DESC)` are left in place. Pruning them is out of scope for this version: the new composite indexes supersede them for the query API, but removing the old ones would touch V1 and is deferred to a follow-up migration once production query plans confirm the new indexes are exclusively used.

## Validation Rules

The controller must validate HTTP syntax, but the domain service must also enforce the query invariants so the rules are not coupled to Spring MVC.

### Request Validation

- `from` is required.
- `to` is required.
- `from` must be strictly earlier than `to`.
- At least one of `actor` or `resource` must be present.
- If provided, `actor` must not be blank.
- If provided, `actor` may contain one actor value or a comma-separated list of actor values.
- The parsed actor list must contain at most 10 actor values.
- If provided, `resource` must not be blank.
- `limit` must be between `1` and `200`.
- `cursor`, if provided, must be a non-blank, decodable continuation token with both `occurredAt` and `id`.

### Invalid Pagination Parameters

Malformed `cursor` values and out-of-range `limit` values are rejected during validation before the repository executes the search.

### Validation Order

Semantic validation in the domain service runs in this fixed order so the first error message is deterministic and useful:

1. time range presence and ordering (`from`/`to` non-null, `from < to`);
2. filter mutex (at least one of `actor` or `resource` is present and non-blank);
3. actor-list parsing rules (`actor` can be parsed into one or more non-blank values and contains at most 10 values);
4. individual blank checks (`resource` not blank if provided);
5. numeric bounds (`limit` in `[1, 200]`);
6. cursor decoding and shape validation when `cursor` is provided.

When both `actor` and `resource` are present but blank, the mutex rule fires first ("at least one of actor or resource is required") rather than the individual blank message, because that is the more actionable client-facing signal.

### Domain Exception

Semantic validation failures are signaled by a dedicated unchecked exception, e.g. `QueryValidationException extends RuntimeException`, defined in the `domain` package. It is distinct from the generic `IllegalArgumentException` used elsewhere in the codebase so the API layer can map it to `422` without affecting the write path.

### Status Mapping

- Use `400` for parse failures, including:
  - malformed timestamp format,
  - malformed cursor payload,
  - non-numeric `limit`,
  - missing required parameters (`from` or `to` absent); these are treated as request-binding failures, not semantic validation.
- Use `422` for semantic validation failures raised by the domain service (`QueryValidationException`).
- Write-path domain validation that already uses `IllegalArgumentException` (e.g. `AuditEventService.record(...)`) keeps its existing `400` mapping. This feature does not remap that exception.

### Safe Validation Errors

Validation failures return compact client-facing errors and must not leak SQL text, stack traces, schema details, or internal database identifiers.


### Boundary Semantics

- `from` is inclusive.
- `to` is exclusive.

This choice avoids overlap when clients split time windows into adjacent ranges.

## Integration With API, Domain, and Infrastructure Layers

### API Layer

Planned API-layer changes:

- Expose `cursor` and `limit` on `GET /audit-events`.
- Keep `actor` as a single query parameter name while allowing one actor value or a comma-separated actor list in that parameter.
- Replace the bare `List<AuditEventResponse>` response with a page envelope, for example `AuditEventSearchResponse`.
- Keep `AuditEventController` focused on request binding, HTTP status codes, and response serialization.
- Extend `ApiExceptionHandler` so semantic query errors can return `422`.

### Domain Layer

Planned domain-layer changes:

- Add domain validation for the new query rules: `from/to` required, `from < to`, at least one of `actor/resource`, multi-actor parsing with a maximum of 10 actor values, limit range, valid cursor when provided.
- Keep the search use case in `AuditEventService` so invariants are enforced outside the controller.

Recommended domain objects:

- `AuditEventSearchCriteria`
- `AuditEventPage`
- `AuditEventCursor`

### Infrastructure Layer

Planned persistence-layer changes:

- Implement `PostgresAuditEventRepository.search(...)` with keyset pagination.
- Keep filtering on `timestamp`, `actor`, and `resource`.
- Translate multi-actor filtering into an `actor IN (...)` predicate over the parsed actor list.
- Order by `timestamp DESC, id DESC`.
- Apply the cursor predicate when `cursor` is present.
- Fetch `limit + 1` rows to determine whether another page exists.
- Reuse the existing actor and resource composite indexes introduced through Flyway.

Only `search(...)` is changed. Other repository methods are explicitly left alone:

- `latest()` continues to order by `sequence_no DESC`. It serves the hash-chain write path and must not be retargeted at the new search ordering.
- `findOlderThan(...)` and `append(...)` are unchanged.

### Tie-Break Change Notice

The repository previously ordered search results by `timestamp DESC, sequence_no DESC`. The new canonical order is `timestamp DESC, id DESC`. This is a behavior change visible to existing search clients: events with identical timestamps may appear in a different relative order after this feature lands. The change is intentional: `id` is a stable per-event identifier exposed in the API, while `sequence_no` is an internal write-path concern. It is worth flagging in the release notes for downstream consumers that compare result ordering between versions.

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

- multi-actor `actor + time range` search;
- `actor + time range` search;
- `resource + time range` search;
- multi-actor cursor-based pagination across multiple pages for a fixed query window;
- cursor-based pagination across multiple pages for a fixed query window;
- deterministic ordering when multiple events share the same timestamp;
- validation failures returning `400` and `422` as designed;
- response shape: `hash` and `sequenceNo` must be absent from search response items.

### Test Clock Requirement

The tie-break test requires multiple events with identical `timestamp` values. Wall-clock seeding cannot reliably produce identical timestamps at the microsecond resolution stored by Postgres, so the test setup must inject a controllable `Clock` (for example, a `TestClock` bean overriding `ClockConfig`) and seed events through `AuditEventService.record(...)` rather than direct SQL inserts. Direct inserts would bypass the append-only invariant and the hash chain, so the clock injection is the supported route.
