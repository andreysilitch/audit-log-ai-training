package com.example.audit.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record AuditEvent(
        UUID id,
        Instant timestamp,
        String actor,
        String action,
        String resource,
        AuditOutcome outcome,
        JsonNode context,
        String prevHash,
        String hash,
        long sequenceNo
) {
}
