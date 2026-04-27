package com.example.audit.api;

import com.example.audit.domain.AuditEvent;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record AuditEventResponse(
    UUID id,
    Instant timestamp,
    String actor,
    String action,
    String resource,
    String outcome,
    JsonNode context,
    long sequenceNo,
    String hash) {

  public static AuditEventResponse of(AuditEvent e) {
    return new AuditEventResponse(
        e.id(),
        e.timestamp(),
        e.actor(),
        e.action(),
        e.resource(),
        e.outcome().name(),
        e.context(),
        e.sequenceNo(),
        e.hash());
  }
}
