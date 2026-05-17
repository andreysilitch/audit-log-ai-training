# Delta: plans vs current spec

The plan set is now aligned with the cursor-based spec in `.specs/query-api/{requirements.md,design.md,tasks.md}`.

## Cursor-related alignments

- T2 validates semantic search rules only; cursor syntax is handled as a parse-path concern.
- T3 introduces `AuditEventCursor`, `cursor`, and `nextCursor`.
- T4 switches repository traversal from `OFFSET` to keyset continuation by `(timestamp, id)`.
- T5 updates the HTTP contract and response envelope to `cursor` / `nextCursor`.
- T6 classifies malformed cursor as `400` and semantic search failures as `422`.
- T7 replaces offset-based traversal tests with cursor-following tests.

## No remaining planned contradictions

- Pagination terminology is consistently cursor-based.
- Sort order remains `occurredAt DESC, id DESC`.
- Default `limit` remains `50`; maximum `limit` remains `200`.
- Search responses remain read-only and omit write-path fields.
