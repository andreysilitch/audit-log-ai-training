package com.example.audit.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record NewAuditEvent(
        Instant timestamp,
        String actor,
        String action,
        String resource,
        AuditOutcome outcome,
        JsonNode context
) {
}
