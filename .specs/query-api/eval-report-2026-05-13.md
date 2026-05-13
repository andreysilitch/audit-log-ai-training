# Specs Self-Evaluation Report: Audit Events Query API

- **Date:** 2026-05-13
- **Scope:** `.specs/query-api/{requirements.md, design.md, tasks.md}`
- **Checklist:** `.specs/_eval-checklist.md` (repo-local)

## Summary

| # | Criterion | Verdict |
|---|---|---|
| 1 | Each AC is testable | PASS |
| 2 | Tasks have refs and DoD | PASS |
| 3 | Dependencies between tasks are explicit | PASS |
| 4 | Every AC in `requirements.md` is addressed by a section in `design.md` | PASS |
| 5 | Every AC traces to a task whose DoD would fail if it regressed | WEAK |
| 6 | No contradictions between the three files | PASS |
| 7 | Cross-references resolve | PASS |
| 8 | Every AC is in EARS form | WEAK |

**Totals:** PASS: 6 · WEAK: 2 · FAIL: 0

## Findings

### 1. Each AC is testable — PASS
All 17 ACs across US1–US3 (`requirements.md` §§ "User Story 1/2/3 — Acceptance Criteria") express observable behavior — endpoint presence, filter shapes, ordering, pagination invariants, validation responses — that can be exercised through HTTP plus database fixtures.

### 2. Tasks have refs and DoD — PASS
Every task (`tasks.md` TASK-1 … TASK-7) has both a `### Refs` block linking design/requirements and a `### DoD` checklist of concrete checkbox items.

### 3. Dependencies between tasks are explicit — PASS
Each task has a `### Dependencies` block and the closing summary table at `tasks.md` § "Summary" mirrors them (e.g. TASK-4 → TASK-1, TASK-3; TASK-7 → TASK-1–6).

### 4. Every AC in `requirements.md` is addressed by a section in `design.md` — PASS
Spot-mapped each AC to a design section: US1.1/1.2/1.3 → § "API Contract / Query Parameters"; US1.4 → § "Response Shape" + "Field Types"; US1.5 / US2.3 / US3.5 → § "Sort and Determinism" and "Tie-Breaker Rule"; US1.6 / US3.6 → § "Validation Rules" + "Status Codes" + "Error Body"; US2.1/2.2 → § "Query Parameters" + "Query Patterns Covered"; US2.4 → § "Contract Decisions" ("occurredAt mapped from persisted timestamp"); US2.5 → § "Contract Decisions" ("empty match returns 200 OK with items: []"); US3.1–3.4 → § "Pagination Strategy" + "`hasMore` and `nextOffset`".

### 5. Every AC traces to a task whose DoD would fail if it regressed — WEAK
Most ACs are traced (US1.5, US2.5, US3.5 → TASK-4/-7 deterministic-order and empty-result DoDs; US3.6 → TASK-2 + TASK-7 `limit` bounds; US1.6 → TASK-6 envelope shape). Gaps:
- **US1.4 (returned fields: id, occurredAt, actor, resource, action, outcome, context)** — no DoD asserts the full response field set. TASK-5 only pins `occurredAt` mapping; a regression that drops or renames `outcome`/`context` would not be caught.
- **US1.6 (safe error: no SQL/stack-trace leakage)** — TASK-6 DoD fixes the error body shape but does not assert the absence of SQL/stack-trace fragments; a logging-leak regression would slip through.
- **US3.3 (response contains at most `limit` items)** — neither TASK-3 nor TASK-5 has an explicit `items.size() <= limit` assertion; TASK-7 covers pagination flow but not the per-page cap as a named check.

### 6. No contradictions between the three files — PASS
Key facts agree across all three: required `from/to` (req § "Resolved Decisions" #1 / design § "Query Parameters" / task DoDs), mutex `actor`+`resource` (#2), canonical `occurredAt DESC, id DESC` (#3 / § "Sort and Determinism" / TASK-4), envelope shape with `items` + `page` (#4 / § "Response Shape" / TASK-3 + TASK-5), `limit` default 50 / max 200 (#6 / § "Query Parameters" / TASK-5), single `context` field name (#7 / § "Contract Decisions"). No mismatched facts surfaced.

### 7. Cross-references resolve — PASS
All `tasks.md` `Refs` anchors resolve to real headings: `design.md#indexes`, `#migration-plan`, `#validation-rules`, `#response-shape`, `#hasmore-and-nextoffset`, `#pagination-strategy`, `#sort-and-determinism`, `#contract-decisions`, `#status-codes`, `#status-mapping`, `#integration-testing-expectations`, plus the three `requirements.md#user-story-…` anchors. No dangling `§X.Y` or "see Section …" pointers were found in `requirements.md` or `design.md`.

### 8. Every AC is in EARS form — WEAK
Only a subset use canonical EARS phrasing:
- **Unwanted-behavior form** (✓): US1.6 ("If request parameters are invalid, the API returns …"), US2.5 ("If no matching events exist, the API returns …"), US3.6 ("rejects invalid pagination parameters").
- **Event-driven form** (✓ partial): US3.2 ("when a client follows the returned continuation values …"), US3.4 ("when more results are available").
- **Not EARS** (✗): US1.1–1.5, US2.1–2.4, US3.1, US3.3, US3.5 are written as declarative present-tense statements ("The endpoint supports …", "Results are ordered …", "The API defines …"). They lack the modal `shall` and the EARS opener (`Ubiquitous` / `When` / `While` / `Where`). Intent is clear and ubiquitous-equivalent, but the form does not match AGENTS.md rule 15.

## Recommended next steps

- Reword the non-EARS ACs (US1.1–1.5, US2.1–2.4, US3.1, US3.3, US3.5) into ubiquitous form (`The audit query API shall …`) to close criterion 8.
- Add explicit DoDs to close criterion 5: full-field response assertion in TASK-5 (covers US1.4), an error-body negative test in TASK-6 or TASK-7 verifying no SQL/stack-trace leakage (US1.6), and an `items.size() <= limit` assertion in TASK-3 or TASK-7 (US3.3).
