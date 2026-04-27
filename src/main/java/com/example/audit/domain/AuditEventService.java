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

  public List<AuditEvent> search(AuditEventSearchCriteria criteria) {
    if (criteria.from() == null || criteria.to() == null) {
      throw new IllegalArgumentException("time range is required");
    }
    if (!criteria.from().isBefore(criteria.to())) {
      throw new IllegalArgumentException("from must be before to");
    }
    if (criteria.limit() <= 0 || criteria.limit() > 1000) {
      throw new IllegalArgumentException("limit must be between 1 and 1000");
    }
    if (criteria.offset() < 0) {
      throw new IllegalArgumentException("offset must be non-negative");
    }
    return repository.search(criteria);
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
  }
}
