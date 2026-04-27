package com.example.audit.api;

import com.example.audit.domain.AuditOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Client-supplied fields only. Server-managed fields (timestamp, id, hash chain, sequence_no) are
 * never accepted from the client (AGENTS.md architectural rule 2).
 */
public record AuditEventRequest(
    @NotBlank(message = "actor is required") String actor,
    @NotBlank(message = "action is required") String action,
    @NotBlank(message = "resource is required") String resource,
    @NotNull(message = "outcome is required") AuditOutcome outcome,
    JsonNode context) {}
