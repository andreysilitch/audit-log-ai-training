package com.example.audit.api;

import java.time.Instant;

public record AuditEventSearchRequest(
        String actor,
        String resource,
        Instant from,
        Instant to,
        Integer limit,
        Integer offset
) {
}
