package com.example.audit.domain;

import java.time.Instant;
import java.util.List;

public record AuditEventSearchCriteria(
    String actor, String resource, Instant from, Instant to, int limit, AuditEventCursor cursor) {

  public List<String> parsedActors() {
    if (actor == null) {
      return List.of();
    }

    return List.of(actor.split(",", -1)).stream().map(String::trim).toList();
  }
}
