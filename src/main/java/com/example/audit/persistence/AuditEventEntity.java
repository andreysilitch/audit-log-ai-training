package com.example.audit.persistence;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Internal persistence-layer projection of a row in {@code audit_events}.
 * Not exposed beyond the persistence package.
 */
record AuditEventEntity(
        UUID id,
        Instant timestamp,
        String actor,
        String action,
        String resource,
        String outcome,
        JsonNode context,
        String prevHash,
        String hash,
        long sequenceNo
) {
}
