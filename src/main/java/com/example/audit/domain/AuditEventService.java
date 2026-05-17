package com.example.audit.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AuditEventService {

  private final AuditEventRepository repository;
  private final Clock clock;

  public AuditEventService(AuditEventRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  public AuditEvent record(
      String actor, String action, String resource, AuditOutcome outcome, JsonNode context) {
    requireNonBlank(actor, "actor");
    requireNonBlank(action, "action");
    requireNonBlank(resource, "resource");
    if (outcome == null) {
      throw new IllegalArgumentException("outcome is required");
    }
    JsonNode safeContext =
        (context == null || context.isNull()) ? JsonNodeFactory.instance.objectNode() : context;
    // Truncate to PostgreSQL TIMESTAMPTZ precision (microseconds) so that the
    // value used for the hash matches the value that round-trips through the DB.
    Instant timestamp = clock.instant().truncatedTo(ChronoUnit.MICROS);
    return repository.append(
        new NewAuditEvent(timestamp, actor, action, resource, outcome, safeContext));
  }

  public AuditEventPage search(AuditEventSearchCriteria criteria) {
    if (criteria.from() == null || criteria.to() == null) {
      throw new QueryValidationException("from and to are required");
    }
    if (!criteria.from().isBefore(criteria.to())) {
      throw new QueryValidationException("from must be before to");
    }
    boolean actorBlank = criteria.actor() == null || criteria.actor().isBlank();
    boolean resourceBlank = criteria.resource() == null || criteria.resource().isBlank();
    if (actorBlank && resourceBlank) {
      throw new QueryValidationException("at least one of actor or resource is required");
    }
    if (criteria.actor() != null && criteria.actor().isBlank()) {
      throw new QueryValidationException("actor must not be blank");
    }
    if (criteria.resource() != null && criteria.resource().isBlank()) {
      throw new QueryValidationException("resource must not be blank");
    }
    if (criteria.limit() < 1 || criteria.limit() > 200) {
      throw new QueryValidationException("limit must be between 1 and 200");
    }
    List<AuditEvent> rows = repository.search(criteria);
    boolean hasMore = rows.size() > criteria.limit();
    List<AuditEvent> items =
        hasMore ? List.copyOf(rows.subList(0, criteria.limit())) : List.copyOf(rows);
    String nextCursor = hasMore ? AuditEventCursor.of(items.get(items.size() - 1)).encode() : null;
    String currentCursor = criteria.cursor() == null ? null : criteria.cursor().encode();
    return new AuditEventPage(items, criteria.limit(), currentCursor, nextCursor, hasMore);
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
  }
}
