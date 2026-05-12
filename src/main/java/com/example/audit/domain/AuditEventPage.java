package com.example.audit.domain;

import java.util.List;

public record AuditEventPage(
    List<AuditEvent> items, int limit, int offset, Integer nextOffset, boolean hasMore) {}
