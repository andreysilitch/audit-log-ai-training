# audit-log

Internal audit service for ingesting, storing and searching the company's audit events. Used by compliance officers, SRE and security analysts. Priorities: correctness, immutability of records, durable storage, convenient search.

Detailed requirements and invariants — see [`AGENTS.md`](AGENTS.md).

## Stack

- Java 21
- Spring Boot 3.3
- Gradle Kotlin DSL (wrapper in repo)
- PostgreSQL 16 (`JSONB`, triggers, advisory locks)
- Flyway for schema migrations
- Testcontainers for integration tests

## Project layout

```
src/main/java/com/example/audit
  AuditApplication.java        # Spring entry point
  ClockConfig.java             # Clock bean

  api/                         # HTTP layer
    AuditEventController.java
    AuditEventRequest.java
    AuditEventResponse.java
    AuditEventSearchRequest.java
    ApiExceptionHandler.java

  domain/                      # business rules and invariants
    AuditEvent.java
    AuditOutcome.java
    AuditEventRepository.java  # append-only contract
    AuditEventService.java
    AuditEventSearchCriteria.java
    NewAuditEvent.java

  persistence/                 # JdbcTemplate + Flyway
    PostgresAuditEventRepository.java
    AuditEventEntity.java

  retention/                   # archival
    RetentionPolicyService.java
    ArchivalService.java

  tamper/                      # hash chain
    HashChainService.java

src/main/resources
  application.yml
  db/migration/V1__create_audit_events.sql

src/integrationTest/java       # integration tests on Testcontainers
src/test/java                  # unit tests (empty for now)
```

## API

### `POST /audit-events`

Record a new audit event. Server sets `id`, `timestamp`, `sequence_no`, `prev_hash`, `hash` itself. Client must not send these fields.

```bash
curl -X POST http://localhost:8080/audit-events \
  -H 'Content-Type: application/json' \
  -d '{
    "actor":    "alice@example.com",
    "action":   "user.login",
    "resource": "user:42",
    "outcome":  "SUCCESS",
    "context":  {"ip": "10.0.0.1"}
  }'
```

Response `201 Created`:

```json
{
  "id": "8b8b...",
  "timestamp": "2026-04-26T19:00:00.000123Z",
  "actor": "alice@example.com",
  "action": "user.login",
  "resource": "user:42",
  "outcome": "SUCCESS",
  "context": {"ip": "10.0.0.1"},
  "sequenceNo": 42,
  "hash": "..."
}
```

`outcome` — one of `SUCCESS`, `DENIED`, `ERROR`. `actor`, `action`, `resource` are required and cannot be empty or whitespace-only.

### `GET /audit-events`

Search by `actor`, `resource` and a mandatory time range (half-open interval `[from, to)`). Pagination via `limit` (default 100, max 1000) and `offset`.

```bash
curl 'http://localhost:8080/audit-events?actor=alice@example.com&from=2026-04-01T00:00:00Z&to=2026-04-30T00:00:00Z&limit=100&offset=0'
```

Time range is mandatory — full table scan is forbidden by design.

### Actuator

`GET /actuator/health`, `/actuator/info`, `/actuator/metrics`, `/actuator/prometheus`.

## Running locally

### 1. Start PostgreSQL

```bash
docker compose up -d postgres
```

`postgres:16-alpine`, port `5432`, db/user/password `audit/audit/audit`.

### 2. Start the application

```bash
./gradlew bootRun
```

Listens on `:8080`. Flyway applies `V1__create_audit_events.sql` on startup.

### 3. Or run the whole stack in containers

```bash
docker compose --profile app up --build
```

## Tests

```bash
./gradlew test              # unit tests (no DB)
./gradlew integrationTest   # integration tests on Testcontainers
./gradlew check             # both
```

Integration tests start `postgres:16-alpine` themselves — Docker must be running.

### Coverage

| Test class | What it checks |
|---|---|
| `AuditEventServiceIntegrationTest` | append + chain, search, validation, append-only DB triggers, tamper detection |
| `AuditEventControllerIntegrationTest` | HTTP contract, validation errors, ignoring client-supplied `timestamp` |
| `PostgresAuditEventRepositoryIntegrationTest` | pagination, half-open time interval, `resource` filter, `latest()`, `findOlderThan` ordering |
| `RetentionPolicyIntegrationTest` | archival, idempotency, no-op when nothing is overdue |
| `ConcurrentAppendIntegrationTest` | parallel appends preserve hash chain validity |

## Invariants we hold

- **Append-only at the DB level** — `BEFORE UPDATE` and `BEFORE DELETE` triggers raise `audit_events is append-only`.
- **`actor`, `action`, `resource`, `outcome`, `timestamp` are `NOT NULL`** + CHECK constraints for non-emptiness and allowed `outcome` values.
- **`context` is `JSONB`**, never a free-form string.
- **`timestamp` is set by the server** (rounded to microseconds for stable round-trip through `TIMESTAMPTZ`).
- **Repository contract** — only `append`, `search`, `latest`, `findOlderThan`. No public `update` / `deleteById` / `deleteAll`.
- **Search requires a time range** and relies on indexes.
- **Hash chain in the write path** — SHA-256 over `prev_hash` + canonical serialization of the event under a tx-scoped advisory lock, so `prev_hash` order is consistent. Canonical JSON form with recursively sorted keys — JSONB round-trip does not break verification.
- **Retention does not delete from the main table.** `RetentionPolicyService` copies overdue events into `audit_events_archive`. Physical deletion is a separate decision (see AGENTS.md, invariant 5).
- **API errors do not leak SQL, stack traces or schema details.**

## Configuration

Via `application.yml` or environment variables.

| Parameter | Default | Purpose |
|---|---|---|
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/audit` | Postgres URL |
| `spring.datasource.username` / `.password` | `audit` / `audit` | Postgres credentials |
| `audit.retention.days` | `90` | retention window before archival |
| `audit.retention.cron` | `0 0 3 * * *` | retention cron (Spring, 6 fields) |
| `server.port` | `8080` | HTTP port |
