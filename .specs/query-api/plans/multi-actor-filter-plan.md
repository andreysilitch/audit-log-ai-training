# Multi-Actor Filter Implementation Plan

## Summary

Implement the new multi-actor query capability for `GET /audit-events` so `actor` accepts either one value or a comma-separated list of up to 10 actors, while preserving the existing cursor-based pagination contract and deterministic ordering.

No schema migration is required for this feature beyond the query-index work already defined in the existing query API plan. The implementation must reuse the current actor composite index strategy already documented in `design.md`.

## Key Changes

### Domain and validation

- Change the search criteria model so the domain works with a normalized actor list, not a raw single `actor` string.
- Keep `actor` optional overall, but when provided parse it into one or more actor values.
- Normalize actor values by splitting on `,`, trimming surrounding whitespace for each token, and rejecting any empty token.
- Enforce the actor-list limit in the domain layer: more than 10 parsed actor values returns `422` via `QueryValidationException`.
- Keep the existing query invariants unchanged: `from` and `to` required, `from < to`, at least one of `actor` or `resource`, `limit` in `[1, 200]`, safe errors only.
- Treat single-value actor and one-item actor list as the same query semantics.
- Preserve the parsed actor list as part of the fixed query scope used across cursor traversal.

### API and repository behavior

- Keep the public query parameter name as `actor`; do not introduce repeated `actor=` parameters for this version.
- Keep the response contract unchanged: same `items` shape, same `page.cursor` / `page.nextCursor` / `page.hasMore`.
- In the controller, continue accepting raw `actor` as `String`, but hand parsing responsibility to the domain/service layer so the invariant is not MVC-only.
- Update repository search to translate the normalized actor list into:
  - `actor = ?` for one actor
  - `actor IN (?, ..., ?)` for multiple actors
- Keep all other search predicates conjunctive: multi-actor OR semantics inside `actor`, then `AND` with `resource`, `from`, `to`, and cursor predicate.
- Preserve canonical ordering and keyset traversal exactly as today: `timestamp DESC, id DESC` plus the existing cursor predicate on `(timestamp, id)`.

### Non-goals and compatibility

- Do not change write-path DTOs, append-only behavior, hash-chain logic, retention logic, or authorization behavior.
- Do not add a new index for this feature unless implementation work uncovers a direct contradiction with the current design; if that happens, the spec must be updated first.
- Do not change cursor encoding or response field names.

## Test Plan

- Unit test actor parsing for:
  - single actor
  - comma-separated list
  - surrounding whitespace trimming
  - empty token rejection such as `a1,,a3`, `actor=`, or `a1, ,a3`
  - more than 10 parsed actors
- Unit test `AuditEventService.search()` to confirm multi-actor validation still composes correctly with existing time-range, resource, limit, and cursor validation.
- Integration test `actor + time range` for a multi-actor list and confirm events for any listed actor are returned.
- Integration test `multi-actor + resource + time range` and confirm OR semantics within actor plus AND semantics against resource.
- Integration test multi-page cursor traversal for a multi-actor query scope and verify no loss or duplication across pages.
- Integration test deterministic ordering remains `timestamp DESC, id DESC` when several returned events share the same timestamp.
- Integration test `422 Unprocessable Entity` when parsed actor count exceeds 10.
- Run full `./gradlew build` before push.

## Assumptions and defaults

- `actor=u_42` remains valid and is treated as a one-item actor list.
- Actor parsing trims surrounding spaces per item; spaces are not preserved as part of actor identity.
- Blank parsed actor items are invalid and return `422`; they are not silently dropped.
- The 10-actor cap is enforced on the parsed actor list after splitting and trimming.
- Exact duplicate actor values may be preserved in parsing but should be de-duplicated before repository query construction if convenient; this must not change result semantics or cursor behavior.
- Existing composite actor index strategy documented in `design.md` is accepted as sufficient for this feature.
