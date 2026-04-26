package com.example.audit.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository contract for audit events.
 * Append-only: no update/delete operations exposed (AGENTS.md invariants 1, 7).
 */
public interface AuditEventRepository {

    AuditEvent append(NewAuditEvent newEvent);

    List<AuditEvent> search(AuditEventSearchCriteria criteria);

    Optional<AuditEvent> latest();

    List<AuditEvent> findOlderThan(Instant cutoff, int limit);
}
