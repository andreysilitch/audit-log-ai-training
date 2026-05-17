# Codex hooks

`spec-stop-gate.ps1` snapshots `.specs/<feature>/` files at `UserPromptSubmit` and, on `Stop`, runs the repository's `spec-self-eval` procedure for each touched feature folder.

It also records feature folders explicitly referenced in the user prompt through paths such as `.specs/query-api/requirements.md` or `.specs\query-api\design.md`. On `Stop`, those referenced features are evaluated even if the turn only read the spec and did not modify any `.specs` files.

The hook evaluates the spec against `.specs/_eval-checklist.md` when present, falls back to `.codex/skills/spec-self-eval/references/_eval-checklist.md`, writes `.specs/<feature>/eval-report-<UTC-date>.md`, and blocks turn close if the report contains any `FAIL` findings.
