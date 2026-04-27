package com.example.audit.domain;

import java.time.Instant;

public record AuditEventSearchCriteria(
    String actor, String resource, Instant from, Instant to, int limit, int offset) {}
