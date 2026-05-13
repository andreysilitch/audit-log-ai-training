---
name: spec-self-eval
description: Self-evaluate a feature spec under `.specs/<feature>/` against a checklist and emit a PASS / FAIL / WEAK report. Use when the user asks to audit, validate, self-check, or score a spec, or types `/spec-self-eval`. Reads `references/_eval-checklist.md` (or `.specs/_eval-checklist.md` if present), validates `requirements.md` / `design.md` / `tasks.md`, and writes `.specs/<feature>/eval-report-<date>.md`.
---

# spec-self-eval

Audit a feature spec (`requirements.md`, `design.md`, `tasks.md`) against an evaluation checklist and emit a per-criterion **PASS / FAIL / WEAK** report.

## Inputs

- **Feature** — `<feature>` slug, matching a directory under `.specs/<feature>/`.
  - If omitted, ask the user; if exactly one feature directory exists under `.specs/`, default to it and confirm.
- **Checklist source** (resolve in this order, first match wins):
  1. `.specs/_eval-checklist.md` (repo-local, project-authoritative)
  2. `references/_eval-checklist.md` (bundled fallback, alongside this SKILL.md)
- **Spec files** — `.specs/<feature>/requirements.md`, `.specs/<feature>/design.md`, `.specs/<feature>/tasks.md`. If any are missing, stop and report which.

## Procedure

1. Resolve the checklist path per the order above. Read every checklist item.
2. Read the three spec files in full.
3. For **each** checklist item, decide a verdict:
   - **PASS** — criterion is satisfied; cite the concrete evidence (file + section or AC id).
   - **WEAK** — partially satisfied; specify what is thin, missing, or ambiguous.
   - **FAIL** — not satisfied; point at the gap or contradiction.
   Evidence must be **one line** per item, naming the file and section/AC. No verdict without evidence.
4. Build the report (see template) and write it to:
   `.specs/<feature>/eval-report-<YYYY-MM-DD>.md`
   Use today's date (UTC) as `<YYYY-MM-DD>`. If a report for today already exists, overwrite it after warning the user.
5. After writing, print a one-line summary to the user: counts of PASS / WEAK / FAIL and the report path.

## Report template

```markdown
# Specs Self-Evaluation Report: <Feature Title>

- **Date:** <YYYY-MM-DD>
- **Scope:** `.specs/<feature>/{requirements.md, design.md, tasks.md}`
- **Checklist:** <resolved checklist path>

## Summary

| # | Criterion | Verdict |
|---|---|---|
| 1 | <checklist item 1>  | PASS \| WEAK \| FAIL |
| 2 | <checklist item 2>  | PASS \| WEAK \| FAIL |
| … | …                   | …                    |

**Totals:** PASS: N · WEAK: N · FAIL: N

## Findings

### 1. <Criterion 1> — <Verdict>
<One-line evidence: file + section/AC. Then, only if WEAK/FAIL, 1–3 bullets pinpointing what is missing or contradictory.>

### 2. <Criterion 2> — <Verdict>
…

## Recommended next steps

- <Highest-impact fix>
- <Next fix>
- <…>
```

## Rules

- Do **not** edit `requirements.md`, `design.md`, or `tasks.md` — this skill only reads and reports.
- Do not invent checklist items. If the checklist has 8 items, the report has 8 rows.
- Prefer the repo-local checklist; mention which source was used in the report header.
- Keep evidence **concrete**: cite AC ids, section headings, or task ids. "Looks fine" is not evidence.
- If the spec references things that do not exist (broken §X.Y, AC ids, "see Section …"), that is a **FAIL** on the cross-reference criterion — list each broken pointer.
- Verdicts use exactly `PASS`, `WEAK`, or `FAIL` (uppercase, no synonyms).
