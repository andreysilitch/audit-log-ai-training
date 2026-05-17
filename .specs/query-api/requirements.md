# Requirements: Audit Events Query API

## Problem

Compliance, incident response, and security investigations need a reliable read-only way to retrieve audit events from the audit service. Today, the service must support querying stored audit events by actor, resource, and time range without compromising append-only storage guarantees or exposing write capabilities.

The query API must help different operational roles answer different questions:

- A compliance officer must be able to confirm or refute whether a specific actor performed an action during a defined audit window.
- An SRE must be able to reconstruct the timeline of actions on a resource during an incident.
- A security analyst must be able to page through large result sets without missing or duplicating events.

The feature is a read-only HTTP endpoint, for example:

```http
GET /audit-events?actor=u_42,svc_billing,svc_auth&resource=order/9f3b...&from=2026-04-01T00:00:00Z&to=2026-05-01T00:00:00Z&cursor=eyJvY2N1cnJlZEF0IjoiMjAyNi0wNC0zMFQxMjozNDo1NloiLCJpZCI6IjEyMzQ1In0&limit=50
```

## User Stories with AC

### User Story 1: Compliance search by actor and time range

As a compliance officer, I want to retrieve audit events for a specific actor within a defined time range so that I can confirm or refute whether an action happened during an audit.

#### Acceptance Criteria

##### US1.AC1
1. The API shall expose a read-only `GET /audit-events` endpoint.
##### US1.AC2
2. The API shall support filtering by `actor`.
##### US1.AC2a
3. The API shall accept `actor` as either a single value or a comma-separated list of actor values, and a multi-actor request shall return events matching any provided actor value within the same query scope.
##### US1.AC3
4. The API shall support filtering by `from` and `to` as an explicit UTC time range.
##### US1.AC4
5. The API shall return event id, occurred timestamp, actor, resource, action, outcome, and context for each matching event.
##### US1.AC5
6. The API shall return results in a deterministic order so repeated requests over the same dataset produce the same sequence.
##### US1.AC6
7. If request parameters are invalid, the API shall return a safe validation error without leaking SQL text, stack traces, or internal database details.

### User Story 2: Incident reconstruction by resource and time range

As an SRE, I want to retrieve audit events for a resource within a time range so that I can reconstruct the sequence of actions during an incident.

#### Acceptance Criteria

##### US2.AC1
1. The API shall support filtering by `resource`.
##### US2.AC2
2. The API shall support combining `resource` with `from` and `to`.
##### US2.AC3
3. The API shall return results in a deterministic chronological order that supports timeline reconstruction.
##### US2.AC4
4. The API shall preserve server-recorded event timestamps as the source of truth in the response.
##### US2.AC5
5. If no matching events exist, the API shall return an empty result set instead of an error.

### User Story 3: Stable cursor-based pagination for large investigations

As a security analyst, I want to paginate through a large result set without loss or duplication so that I can review all matching audit events safely.

#### Acceptance Criteria

##### US3.AC1
1. The API shall support cursor-based pagination through `cursor` and `limit`, where `cursor` identifies the continuation position in the deterministic result order and `limit` is the maximum number of events to return in one page.
##### US3.AC2
2. When a client follows returned cursor values through the same query scope, the API shall paginate without skipping or duplicating events between pages.
##### US3.AC3
3. The API shall return at most `limit` events in one page.
##### US3.AC4
4. When more results are available, the API shall return a continuation value such as `nextCursor` and shall signal when no further pages exist.
##### US3.AC5
5. The API shall define a deterministic tie-break strategy for events with identical timestamps so page boundaries remain stable.
##### US3.AC5a
6. When a client paginates through a query scope that uses a multi-actor `actor` filter, the API shall preserve the same cursor-based stability guarantees as for a single-actor query scope.
##### US3.AC6
7. If pagination parameters are invalid, the API shall return a safe client error for cases such as a malformed `cursor` or a `limit` outside the allowed range.
##### US3.AC6a
8. If the parsed `actor` list contains more than 10 actor values, the API shall return `422 Unprocessable Entity`.

## Out of Scope

- Creating, updating, or deleting audit events.
- Changing append-only storage behavior.
- Retention, archival, or data lifecycle workflows.
- Tamper-evidence or hash-chain verification changes.
- Free-text search across arbitrary payload fields.
- Bulk export workflows beyond normal paginated retrieval.
- Authorization model implementation details beyond the assumption that read access is restricted to approved roles.
- Client-side deduplication logic.

## Resolved Decisions

The following questions have been resolved and the answers are normative for this version. See `design.md` for full rationale.

- **`from` and `to` requirement.** Both `from` and `to` are required on every request. One-sided time bounds are not accepted.
- **`actor` / `resource` filters.** At least one of `actor` or `resource` must be present in addition to the time range. Each is individually optional, but they cannot both be omitted.
- **Multi-actor filter semantics.** `actor` accepts either one actor value or a comma-separated list of actor values. Multi-actor matching uses OR semantics within the `actor` filter and still combines with `resource` and time-range filters using AND semantics.
- **Multi-actor filter limit.** One request may contain at most 10 actor values in the `actor` filter. More than 10 actor values is a semantic validation error and returns `422 Unprocessable Entity`.
- **Canonical sort order.** Results are returned in `occurredAt DESC, id DESC` order. This is also the order used for pagination.
- **Response envelope.** The response is a page envelope with an `items` array and a `page` object carrying pagination metadata (`limit`, `cursor`, `nextCursor`, `hasMore`). It is not a bare event list.
- **`outcome` filter.** `outcome` is only exposed in the response. It is not a query filter in this version.
- **`limit` bounds.** Default page size is `50` when `limit` is omitted. Maximum allowed `limit` is `200`.
- **Multi-actor index strategy.** The implementation must support efficient multi-actor filtering on `actor`, and `design.md` must either define a new index strategy for that filter or explicitly justify why the existing indexed access path is sufficient.
- **`payload` vs `context`.** The endpoint returns a single field named `context`, aligned with the existing domain model and storage schema. `payload` is not returned.

## Open Questions

- **Access control and rate limits.** Concrete access-control rules and rate-limit thresholds for compliance, SRE, and security analyst roles are out of scope for this version. The endpoint assumes authenticated callers with read permission on audit events; finer-grained policy is deferred.
