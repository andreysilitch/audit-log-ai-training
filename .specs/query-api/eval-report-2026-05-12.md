# Specs Self-Evaluation Report: Audit Events Query API

- **Date:** 2026-05-12
- **Scope:** `.specs/query-api/{requirements.md, design.md, tasks.md}`
- **Checklist:** `.specs/_eval-checklist.md`

## Summary

| # | Criterion | Verdict |
|---|---|---|
| 1 | Each AC is testable | WEAK |
| 2 | Tasks have refs and DoD | PASS |
| 3 | Dependencies between tasks are explicit | PASS |
| 4 | Every AC in `requirements.md` is addressed by a section in `design.md` | WEAK |
| 5 | Every AC traces to a task whose DoD would fail if it regressed | WEAK |
| 6 | No contradictions between `requirements.md`, `design.md`, `tasks.md` | FAIL |
| 7 | Cross-references resolve | PASS |
| 8 | Every AC is in EARS form | WEAK |

---

## 1. Each AC is testable — WEAK

Most ACs are operationally testable (call endpoint, assert response/status). Weaknesses:

- **US1 AC6** — "safe validation error without leaking SQL, stack traces, or internal database details." Negative-shape testable (assert absence of `SQLException`, `\n\tat ` lines) but "safe" is subjective. Evidence: `requirements.md:32`.
- **US3 AC1, AC6** — phrased around "cursor-based pagination" / "invalid cursor values", but the implementation delivers offset. The AC is testable in spirit (continuation token rejection), but not as written. Evidence: `requirements.md:52, 57`.

All other ACs map directly to assertions in `tasks.md` T7 scenarios.

## 2. Tasks have refs and DoD — PASS

Every task T1–T7 has a `### Refs` block linking back to `design.md` / `requirements.md` and a `### DoD` checklist. Evidence: `tasks.md:7-22, 31-48, 56-71, 78-95, 102-121, 128-143, 152-175`.

## 3. Dependencies between tasks are explicit — PASS

Each task has a `### Dependencies` section and the bottom summary table restates the graph. Evidence: `tasks.md:20-22, 46-48, 69-71, 91-95, 117-121, 141-143, 172-175, 180-188`.

## 4. Every AC in requirements.md is addressed by a section in design.md — WEAK

Coverage table:

| AC | design.md anchor | OK? |
|---|---|---|
| US1 AC1 read-only GET | `API Contract → Endpoint`, `Contract Decisions` | ✓ |
| US1 AC2 actor filter | `Query Parameters` | ✓ |
| US1 AC3 from/to UTC range | `Query Parameters`, `Boundary Semantics` | ✓ |
| US1 AC4 event fields | `Response Shape`, `Field Types` | ✓ |
| US1 AC5 deterministic order | `Sort and Determinism` | ✓ |
| US1 AC6 safe validation error | `Status Codes`, `Error Body`, `AGENTS.md Alignment → Safe API Errors` | ✓ |
| US2 AC1 resource filter | `Query Parameters` | ✓ |
| US2 AC2 resource + from/to | `Query Patterns Covered` | ✓ |
| US2 AC3 chronological order | `Sort and Determinism` | ✓ |
| US2 AC4 server-recorded timestamps | `Contract Decisions`, `Server-Controlled Time` | ✓ |
| US2 AC5 empty result → ok | `Contract Decisions`, `Status Codes` | ✓ |
| US3 AC1 cursor-based pagination | `Pagination Strategy` (but offset, not cursor) | ✗ terminology mismatch |
| US3 AC2 stable pagination | `Stability and Trade-Offs` | ✓ |
| US3 AC3 at-most-limit | `hasMore and nextOffset`, `Query Parameters` | ✓ |
| US3 AC4 continuation token | `hasMore and nextOffset` | ✓ (offset acts as continuation) |
| US3 AC5 tie-break strategy | `Tie-Breaker Rule` | ✓ |
| US3 AC6 reject invalid cursor | `Status Codes` (rejects invalid offset, not cursor) | ✗ terminology mismatch |

US3 AC1 and AC6 still speak about "cursor". `design.md` and `tasks.md` switched the entire stack to offset. The AC text was never updated.

## 5. Every AC traces to a task whose DoD would fail if it regressed — WEAK

Trace table:

| AC | Task DoD line that breaks on regression |
|---|---|
| US1 AC1 | T5 DoD "Controller returns `AuditEventSearchResponse`"; T7 #1 |
| US1 AC2 | T7 #1 "actor + time range search returns matching events" |
| US1 AC3 | T2 DoD validation; T7 #1 |
| US1 AC4 | T5 DoD field list; T7 #13 `noHashOrSequenceNoLeaked` |
| US1 AC5 | T4 DoD `timestamp DESC, id DESC`; T7 #5 |
| US1 AC6 | T6 DoD error body shape; T7 #9 malformed timestamp |
| US2 AC1 | T7 #2 resource + time range |
| US2 AC2 | T7 #3 combined filter |
| US2 AC3 | T4 DoD canonical order |
| US2 AC4 | T5 DoD `occurredAt` mapping; T7 envelope shape |
| US2 AC5 | T5 DoD "Empty result returns 200 OK with `items: []`"; T7 #6 |
| US3 AC1 cursor | **No task delivers cursor.** All pagination tasks use offset. |
| US3 AC2 | T7 #4 paginates across pages with no loss/dup |
| US3 AC3 | T3 DoD trim to limit |
| US3 AC4 | T3 DoD `nextOffset`/`hasMore` |
| US3 AC5 | T4 DoD tie-break `id DESC`; T7 #5 |
| US3 AC6 | T6 DoD parse errors → 400 (for offset; not cursor) |

US3 AC1 has no DoD that would fail on regression because the spec's promise (cursor) was never implemented. AC6 partially traces — the task covers invalid-offset rejection, not invalid-cursor.

## 6. No contradictions between requirements.md, design.md, tasks.md — FAIL

- **Cursor vs offset.** `requirements.md` US3 AC1 says "cursor-based pagination through `cursor` and `limit`"; AC6 mentions "invalid cursor values". `design.md` `Pagination Strategy` chose offset and removed cursor assumptions; `tasks.md` T3–T5 implement offset. The example URL in `requirements.md:16` also contains `cursor=...`. Evidence: `requirements.md:16, 52, 57` vs `design.md:118-122, 161-166`.
- **`payload/context` in US1 AC4.** `requirements.md:30` still reads "payload/context", but `Resolved Decisions` #7 at `requirements.md:80` already fixed the field as `context` only. Internal inconsistency inside `requirements.md`.
- **`outcome` filter.** `requirements.md` AC list does not mention `outcome` filtering; `Resolved Decisions` #5 says response-only; `design.md` Query Parameters does not list `outcome`. Consistent here — flagged only because the example URL contains `cursor` but never `outcome`, so the asymmetry is intentional.

Minor (non-contradictions): `nextOffset` wording was reconciled in the latest commit (`offset + items.size()` after trimming, equal to `offset + limit` when `hasMore`).

## 7. Cross-references resolve — PASS

- `tasks.md` refs to `design.md#...` and `requirements.md#...` use kebab-case section anchors that match Markdown's auto-generated slugs (e.g. `#indexes`, `#migration-plan`, `#response-shape`, `#hasmore-and-nextoffset`, `#status-codes`, `#status-mapping`, `#integration-testing-expectations`, `#user-story-1-...`).
- `requirements.md#User Story 1 AC6` (used by T2) does not point to a per-AC anchor — there is no `#user-story-1-ac6` heading, only the story-level heading. The reader still lands on the right story. WEAK in principle but functional; classified PASS because the link still resolves to a real section.
- No "see Section §X.Y" style numeric references are used; nothing to dangle.

## 8. Every AC is in EARS form — WEAK

EARS requires the `shall` keyword and one of five canonical structures. The ACs here are EARS-shaped but not EARS-conformant:

| AC sample | Closest EARS form | Conformance issue |
|---|---|---|
| US1 AC1 "The system exposes a read-only `GET /audit-events` endpoint." | Ubiquitous | Uses "exposes" instead of "shall expose". |
| US1 AC2 "The endpoint supports filtering by `actor`." | Ubiquitous | "supports" not "shall support". |
| US1 AC6 "If request parameters are invalid, the API returns a safe validation error..." | Unwanted (`If … then the system shall …`) | Missing "then ... shall". |
| US2 AC5 "If no matching events exist, the API returns an empty result set..." | Unwanted | Missing "shall". |
| US3 AC4 "The API returns a cursor … when more results are available." | Event-driven (`When … the system shall …`) | Missing "shall"; clause order reversed. |
| US3 AC6 "The API rejects invalid cursor values with a safe client error." | Unwanted | Missing "shall" and conditional trigger. |

Semantically each AC matches one of the EARS templates, but none uses the canonical `shall`-form and several put the trigger after the response. WEAK rather than FAIL because intent is unambiguous.

---

## Action Items

1. **Rewrite US3 AC1 and AC6** in `requirements.md` to use offset/continuation-token language. Update the example URL at `requirements.md:16` to drop `cursor=...`.
2. **Fix US1 AC4** at `requirements.md:30` — drop "payload/" so only `context` remains.
3. (Optional) Convert ACs to canonical EARS by adding "shall" and aligning the trigger/response order. Cosmetic but useful for downstream test generation.
