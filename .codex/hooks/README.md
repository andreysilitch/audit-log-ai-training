# Codex hooks

`spec-stop-gate.ps1` snapshots `.specs/<feature>/` files at `UserPromptSubmit` and, on `Stop`, runs the repository's `spec-self-eval` procedure for each touched feature folder.

The hook evaluates the spec against `.specs/_eval-checklist.md` when present, falls back to `.codex/skills/spec-self-eval/references/_eval-checklist.md`, writes `.specs/<feature>/eval-report-<UTC-date>.md`, and blocks turn close if the report contains any `FAIL` findings.
