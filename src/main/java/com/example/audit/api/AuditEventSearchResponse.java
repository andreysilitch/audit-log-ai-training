package com.example.audit.api;

import com.example.audit.domain.AuditEvent;
import com.example.audit.domain.AuditEventPage;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AuditEventSearchResponse(List<Item> items, Page page) {

  public record Item(
      UUID id,
      Instant occurredAt,
      String actor,
      String action,
      String resource,
      String outcome,
      JsonNode context) {

    public static Item of(AuditEvent e) {
      return new Item(
          e.id(),
          e.timestamp(),
          e.actor(),
          e.action(),
          e.resource(),
          e.outcome().name(),
          e.context());
    }
  }

  public record Page(int limit, int offset, Integer nextOffset, boolean hasMore) {}

  public static AuditEventSearchResponse of(AuditEventPage p) {
    return new AuditEventSearchResponse(
        p.items().stream().map(Item::of).toList(),
        new Page(p.limit(), p.offset(), p.nextOffset(), p.hasMore()));
  }
}
