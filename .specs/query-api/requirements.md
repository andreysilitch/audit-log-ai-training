# Requirements: Audit Events Query API

## Problem

Compliance, incident response, and security investigations need a reliable read-only way to retrieve audit events from the audit service. Today, the service must support querying stored audit events by actor, resource, and time range without compromising append-only storage guarantees or exposing write capabilities.

The query API must help different operational roles answer different questions:

- A compliance officer must be able to confirm or refute whether a specific actor performed an action during a defined audit window.
- An SRE must be able to reconstruct the timeline of actions on a resource during an incident.
- A security analyst must be able to page through large result sets without missing or duplicating events.

The feature is a read-only HTTP endpoint, for example:

```http
GET /audit-events?actor=u_42&resource=order/9f3b...&from=2026-04-01T00:00:00Z&to=2026-05-01T00:00:00Z&cursor=...&limit=50
```

## User Stories with AC

### User Story 1: Compliance search by actor and time range

As a compliance officer, I want to retrieve audit events for a specific actor within a defined time range so that I can confirm or refute whether an action happened during an audit.

#### Acceptance Criteria

1. The system exposes a read-only `GET /audit-events` endpoint.
2. The endpoint supports filtering by `actor`.
3. The endpoint supports filtering by `from` and `to` as an explicit UTC time range.
4. Returned events include enough information for investigation: event id, occurred timestamp, actor, resource, action, outcome, and payload/context.
5. Results are ordered deterministically so the same dataset can be reviewed consistently across repeated requests.
6. If request parameters are invalid, the API returns a safe validation error without leaking SQL, stack traces, or internal database details.

### User Story 2: Incident reconstruction by resource and time range

As an SRE, I want to retrieve audit events for a resource within a time range so that I can reconstruct the sequence of actions during an incident.

#### Acceptance Criteria

1. The endpoint supports filtering by `resource`.
2. The endpoint supports combining `resource` with `from` and `to`.
3. Results are sorted in a deterministic chronological order that allows timeline reconstruction.
4. The response preserves server-recorded event timestamps as the source of truth.
5. If no matching events exist, the API returns an empty result set rather than an error.

### User Story 3: Stable pagination for large investigations

As a security analyst, I want to paginate through a large result set without loss or duplication so that I can review all matching audit events safely.

#### Acceptance Criteria

1. The endpoint supports cursor-based pagination through `cursor` and `limit`.
2. Pagination must be stable: when a client follows the returned cursor through the same query scope, events are not skipped or duplicated between pages.
3. The API returns at most `limit` events in one page.
4. The API returns a cursor or equivalent continuation token when more results are available.
5. The API defines a deterministic tie-break strategy for events with identical timestamps, so page boundaries remain stable.
6. The API rejects invalid cursor values with a safe client error.

## Out of Scope

- Creating, updating, or deleting audit events.
- Changing append-only storage behavior.
- Retention, archival, or data lifecycle workflows.
- Tamper-evidence or hash-chain verification changes.
- Free-text search across arbitrary payload fields.
- Bulk export workflows beyond normal paginated retrieval.
- Authorization model implementation details beyond the assumption that read access is restricted to approved roles.
- Client-side deduplication logic.

## Open Questions

1. Must `from` and `to` always be required, or can one-sided time bounds be allowed?
2. Are `actor` and `resource` both optional filters, or must at least one of them be present in addition to the time range?
3. What is the canonical sort order for results and cursor generation: `occurredAt ASC`, `occurredAt DESC`, or another ordering?
4. What exact response envelope is required for pagination: bare event list, list plus `nextCursor`, or another structure?
5. Should `outcome` also be exposed as a filter in the first version, or only in the response?
6. What is the maximum allowed `limit`, and what default page size should the API apply when `limit` is omitted?
7. Should the endpoint return the field name `payload`, `context`, or both, given the current domain terminology?
8. What access-control and rate-limit requirements must be enforced for compliance, SRE, and security analyst roles?
