# audit-log

Внутренний audit-сервис для приёма, хранения и поиска аудит-событий компании. Используется compliance-офицерами, SRE и security-аналитиками. Приоритеты: корректность, неизменяемость записей, надёжность хранения, удобный поиск.

Подробные требования и инварианты — в [`AGENTS.md`](AGENTS.md).

## Стек

- Java 21
- Spring Boot 3.3
- Gradle Kotlin DSL (wrapper в репозитории)
- PostgreSQL 16 (`JSONB`, триггеры, advisory locks)
- Flyway для миграций схемы
- Testcontainers для интеграционных тестов

## Структура проекта

```
src/main/java/com/example/audit
  AuditApplication.java        # точка входа Spring
  ClockConfig.java             # бин Clock

  api/                         # HTTP-слой
    AuditEventController.java
    AuditEventRequest.java
    AuditEventResponse.java
    AuditEventSearchRequest.java
    ApiExceptionHandler.java

  domain/                      # бизнес-правила и инварианты
    AuditEvent.java
    AuditOutcome.java
    AuditEventRepository.java  # append-only контракт
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

src/integrationTest/java       # интеграционные тесты на Testcontainers
src/test/java                  # unit-тесты (пока пусто)
```

## API

### `POST /audit-events`

Запись нового аудит-события. Сервер сам выставляет `id`, `timestamp`, `sequence_no`, `prev_hash`, `hash`. Клиент эти поля передавать не должен.

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

Ответ `201 Created`:

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

`outcome` — одно из `SUCCESS`, `DENIED`, `ERROR`. `actor`, `action`, `resource` обязательны и не могут быть пустыми/только из пробелов.

### `GET /audit-events`

Поиск по `actor`, `resource` и обязательному временному диапазону (полуинтервал `[from, to)`). Пагинация через `limit` (по умолчанию 100, максимум 1000) и `offset`.

```bash
curl 'http://localhost:8080/audit-events?actor=alice@example.com&from=2026-04-01T00:00:00Z&to=2026-04-30T00:00:00Z&limit=100&offset=0'
```

Временной диапазон обязателен — full-scan по таблице запрещён архитектурно.

### Actuator

`GET /actuator/health`, `/actuator/info`, `/actuator/metrics`, `/actuator/prometheus`.

## Запуск локально

### 1. Поднять PostgreSQL

```bash
docker compose up -d postgres
```

`postgres:16-alpine`, порт `5432`, db/user/password `audit/audit/audit`.

### 2. Запустить приложение

```bash
./gradlew bootRun
```

Слушает на `:8080`. Flyway применяет `V1__create_audit_events.sql` при старте.

### 3. Или запустить весь стек в контейнерах

```bash
docker compose --profile app up --build
```

## Тесты

```bash
./gradlew test              # unit-тесты (без БД)
./gradlew integrationTest   # интеграционные на Testcontainers
./gradlew check             # и то и другое
```

Интеграционные тесты сами поднимают `postgres:16-alpine` — нужен запущенный Docker.

### Покрытие

| Тестовый класс | Что проверяет |
|---|---|
| `AuditEventServiceIntegrationTest` | append + цепочка, поиск, валидация, БД-триггеры append-only, детектирование подмены |
| `AuditEventControllerIntegrationTest` | HTTP-контракт, ошибки валидации, игнорирование клиентского `timestamp` |
| `PostgresAuditEventRepositoryIntegrationTest` | пагинация, полуинтервал по времени, фильтр по `resource`, `latest()`, порядок `findOlderThan` |
| `RetentionPolicyIntegrationTest` | archival, идемпотентность, no-op если ничего не просрочено |
| `ConcurrentAppendIntegrationTest` | параллельные append'ы сохраняют валидность hash chain |

## Какие инварианты держим

- **Append-only на уровне БД** — триггеры `BEFORE UPDATE` и `BEFORE DELETE` бросают `audit_events is append-only`.
- **`actor`, `action`, `resource`, `outcome`, `timestamp` — `NOT NULL`** + CHECK-констрейнты на непустоту и допустимые значения `outcome`.
- **`context` — `JSONB`**, никогда не свободная строка.
- **`timestamp` ставит сервер** (с округлением до микросекунд для стабильного round-trip через `TIMESTAMPTZ`).
- **Контракт репозитория** — только `append`, `search`, `latest`, `findOlderThan`. Никаких публичных `update` / `deleteById` / `deleteAll`.
- **Поиск требует time range** и опирается на индексы.
- **Hash chain в write-path** — SHA-256 от `prev_hash` + канонической сериализации события под tx-scoped advisory lock, так что порядок `prev_hash` консистентен. Каноническая JSON-форма с рекурсивно отсортированными ключами — JSONB round-trip не ломает верификацию.
- **Retention не удаляет из основной таблицы.** `RetentionPolicyService` копирует просроченные события в `audit_events_archive`. Физическое удаление — отдельное решение (см. AGENTS.md, инвариант 5).
- **Ошибки API не светят SQL, stack trace или детали схемы.**

## Конфигурация

Через `application.yml` или переменные окружения.

| Параметр | По умолчанию | Назначение |
|---|---|---|
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/audit` | URL Postgres |
| `spring.datasource.username` / `.password` | `audit` / `audit` | креды Postgres |
| `audit.retention.days` | `90` | окно retention перед archival |
| `audit.retention.cron` | `0 0 3 * * *` | cron для retention (Spring, 6 полей) |
| `server.port` | `8080` | HTTP-порт |
