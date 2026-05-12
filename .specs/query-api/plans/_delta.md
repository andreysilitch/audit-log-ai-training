# Delta: plans vs spec

Key gaps only. Format: (1) new in plan → where it should go; (2) contradictions; (3) spec ambiguity.

---

## T1 — Flyway V2 indexes

- **New:** policy on existing V1 indexes `(actor, timestamp DESC)` / `(resource, timestamp DESC)` — keep, do not prune. → `design.md#Indexes`.
- **Ambiguity:** spec is silent on what to do with the now-duplicate V1 indexes.

## T2 — Domain validation

- **New:** validation order (time → mutex → blank → numeric); `QueryValidationException` class hierarchy. → `design.md#Validation Rules`.
- **Ambiguity:** message priority when `actor=""` and `resource=""` at the same time — "at least one required" or "must not be blank"?

## T3 — `AuditEventPage`

- **New:** nullability of `nextOffset` in JSON (`null` vs omitted when `hasMore=false`). → `design.md#Response Shape`.
- **Wording contradiction:** `tasks.md` says `offset + items.size()`, `design.md` says `offset + number_of_returned_items`. Equivalent after trimming, but the text differs.

## T4 — Repository pagination

- **New:** tie-break change `sequence_no → id` is a breaking change for existing search clients. → `design.md#Infrastructure Layer`.
- **Ambiguity:** whether the current SQL already honors `from` inclusive / `to` exclusive — plan does not verify.

## T5 — API envelope

- **New:** explicit ban on `hash` / `sequenceNo` in the read response; `context` typed as `JsonNode`; `outcome` serialized as enum name (`"SUCCESS"`). → `design.md#Response Shape` + `#Contract Decisions`.
- **Contradiction:** `requirements.md` Open Q7 (`payload` vs `context`) is still formally open while `design.md` already chose `context`.

## T6 — 400 vs 422

- **New:** write-path `IllegalArgumentException` stays at 400 (plan does not break existing behavior). → `design.md#Status Mapping`.
- **Ambiguity:** missing required parameter (`from` / `to` absent) — is it 400 (parse) or 422 (semantic)? Spec does not classify.

## T7 — Integration tests

- **New:** `TestClock` for seeding events with identical timestamps (tie-break test). → `design.md#Integration Testing Expectations`.
- **Ambiguity:** spec gives no mechanism for time control yet still requires a tie-break test.

---

## Spec updates needed

| Section | What |
|---|---|
| `requirements.md` Open Questions | close Q1–Q8 (most already resolved in `design.md`, but `requirements.md` is not updated); Q8 (auth) is not covered by any plan |
| `design.md#Response Shape` | `nextOffset` nullability; `context` type; `outcome` format; explicit ban on `hash` / `sequenceNo` |
| `design.md#Validation Rules` | validation order; status for missing required parameter |
| `design.md#Infrastructure Layer` | tie-break breaking change; fate of V1 indexes |
| `design.md#Integration Testing Expectations` | clock injection for tie-break |
