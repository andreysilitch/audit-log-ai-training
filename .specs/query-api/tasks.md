# Tasks: Audit Events Query API

## TASK-1: Flyway Migration for Query API Indexes

Add composite B-tree indexes optimized for the query API's filtering and sorting requirements.

### Refs

- [design.md#Indexes](design.md#indexes)
- [design.md#Migration Plan](design.md#migration-plan)

### DoD

- [ ] New migration file `V2__add_query_api_search_indexes.sql` exists in `src/main/resources/db/migration`.
- [ ] Index `idx_audit_events_actor_timestamp_id` created on `(actor, timestamp DESC, id DESC)`.
- [ ] Index `idx_audit_events_resource_timestamp_id` created on `(resource, timestamp DESC, id DESC)`.
- [ ] Migration applies successfully on a fresh database via `./gradlew flywayMigrate` or application startup.
- [ ] Existing `V1__create_audit_events.sql` is not modified.

### Dependencies

None.

---

## TASK-2: Domain Layer — Search Criteria Validation

Enforce query invariants in the domain/service layer so validation is not coupled to Spring MVC.

### Refs

- [design.md#Validation Rules](design.md#validation-rules)
- [requirements.md#User Story 1 AC6](requirements.md#user-story-1-compliance-search-by-actor-and-time-range)

### DoD

- [ ] `AuditEventService.search()` throws a domain exception when `from` is null or `to` is null.
- [ ] `AuditEventService.search()` throws a domain exception when `from >= to`.
- [ ] `AuditEventService.search()` throws a domain exception when both `actor` and `resource` are absent or blank.
- [ ] `AuditEventService.search()` throws a domain exception when `actor` is provided but blank.
- [ ] `AuditEventService.search()` throws a domain exception when `resource` is provided but blank.
- [ ] `AuditEventService.search()` throws a domain exception when `limit` is outside `[1, 200]`.
- [ ] `AuditEventService.search()` throws a domain exception when `offset` is negative.
- [ ] Unit tests cover all validation cases.

### Dependencies

None.

---

## TASK-3: Domain Layer — Paginated Result Model

Introduce `AuditEventPage` to carry search results with pagination metadata.

### Refs

- [design.md#Response Shape](design.md#response-shape)
- [design.md#`hasMore` and `nextOffset`](design.md#hasmore-and-nextoffset)

### DoD

- [ ] New record `AuditEventPage` in `domain` package with fields: `items` (list of `AuditEvent`), `limit`, `offset`, `nextOffset` (nullable), `hasMore`.
- [ ] `AuditEventService.search()` returns `AuditEventPage` instead of `List<AuditEvent>`.
- [ ] `nextOffset` is computed as `offset + items.size()` when `hasMore` is true; otherwise `null`.
- [ ] Unit tests verify `hasMore` and `nextOffset` calculation logic.

### Dependencies

- TASK-2 (validation must be in place before changing return type).

---

## TASK-4: Repository — Offset Pagination with hasMore Detection

Update repository to fetch `limit + 1` rows and support deterministic `timestamp DESC, id DESC` ordering.

### Refs

- [design.md#Pagination Strategy](design.md#pagination-strategy)
- [design.md#Sort and Determinism](design.md#sort-and-determinism)

### DoD

- [ ] `PostgresAuditEventRepository.search()` orders results by `timestamp DESC, id DESC`.
- [ ] Repository fetches `limit + 1` rows to detect whether more results exist.
- [ ] Repository returns a structure or uses a convention that allows the service to determine `hasMore`.
- [ ] Existing ordering by `sequence_no` is replaced with `id` for tie-breaking.
- [ ] Integration test confirms deterministic order when multiple events share the same timestamp.

### Dependencies

- TASK-1 (indexes must exist for efficient queries).
- TASK-3 (service expects enriched result for pagination).

---

## TASK-5: API Layer — Paginated Response Envelope

Replace bare `List<AuditEventResponse>` with a page envelope `AuditEventSearchResponse`.

### Refs

- [design.md#Response Shape](design.md#response-shape)
- [design.md#Contract Decisions](design.md#contract-decisions)

### DoD

- [ ] New DTO `AuditEventSearchResponse` with `items` (list) and nested `page` object containing `limit`, `offset`, `nextOffset`, `hasMore`.
- [ ] `AuditEventController.search()` returns `AuditEventSearchResponse`.
- [ ] Response field `occurredAt` maps from domain `timestamp` (not `timestamp` in JSON).
- [ ] Default `limit` is `50`; maximum `limit` is `200`.
- [ ] Default `offset` is `0`.
- [ ] Empty result returns `200 OK` with `"items": []`.
- [ ] Integration test verifies response shape matches design contract.

### Dependencies

- TASK-3 (service returns `AuditEventPage`).
- TASK-4 (repository provides data for pagination metadata).

---

## TASK-6: API Layer — Differentiate 400 vs 422 Errors

Extend `ApiExceptionHandler` to return `422 Unprocessable Entity` for semantic validation failures.

### Refs

- [design.md#Status Codes](design.md#status-codes)
- [design.md#Status Mapping](design.md#status-mapping)

### DoD

- [ ] New custom exception (e.g., `QueryValidationException`) thrown by domain layer for semantic errors.
- [ ] `ApiExceptionHandler` maps `QueryValidationException` to HTTP `422`.
- [ ] Parse errors (malformed timestamp, non-numeric offset) remain `400`.
- [ ] Error body follows design: `{"error": "validation_failed", "message": "..."}`.
- [ ] Integration tests verify `400` for parse errors and `422` for semantic validation errors.

### Dependencies

- TASK-2 (domain validation throws distinguishable exceptions).

---

## TASK-7: Integration Tests for Query API

Add Testcontainers-based integration tests covering query scenarios from design.

### Refs

- [design.md#Integration Testing Expectations](design.md#integration-testing-expectations)
- [requirements.md#User Story 1](requirements.md#user-story-1-compliance-search-by-actor-and-time-range)
- [requirements.md#User Story 2](requirements.md#user-story-2-incident-reconstruction-by-resource-and-time-range)
- [requirements.md#User Story 3](requirements.md#user-story-3-stable-pagination-for-large-investigations)

### DoD

- [ ] Test: `actor + time range` search returns matching events.
- [ ] Test: `resource + time range` search returns matching events.
- [ ] Test: `actor + resource + time range` combined filter works.
- [ ] Test: Offset pagination across multiple pages for a fixed query window returns all events without loss or duplication.
- [ ] Test: Deterministic ordering verified when multiple events share the same timestamp.
- [ ] Test: Empty result returns `200 OK` with `"items": []`.
- [ ] Test: Missing both `actor` and `resource` returns `422`.
- [ ] Test: `from >= to` returns `422`.
- [ ] Test: Malformed timestamp returns `400`.
- [ ] Test: `limit` outside `[1, 200]` returns `422`.
- [ ] All tests use Testcontainers with PostgreSQL.

### Dependencies

- TASK-1 through TASK-6 (full implementation required).

---

## Summary

| Task | Description | Dependencies |
|------|-------------|--------------|
| TASK-1 | Flyway migration for indexes | — |
| TASK-2 | Domain validation rules | — |
| TASK-3 | `AuditEventPage` model | TASK-2 |
| TASK-4 | Repository pagination + ordering | TASK-1, TASK-3 |
| TASK-5 | API response envelope | TASK-3, TASK-4 |
| TASK-6 | 400 vs 422 error handling | TASK-2 |
| TASK-7 | Integration tests | TASK-1–6 |

