package com.example.audit.domain;

import java.util.List;

public record AuditEventPage(
    List<AuditEvent> items, int limit, String cursor, String nextCursor, boolean hasMore) {}
