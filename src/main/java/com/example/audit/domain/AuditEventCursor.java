package com.example.audit.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

public record AuditEventCursor(Instant occurredAt, UUID id) {

  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  public AuditEventCursor {
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(id, "id");
  }

  public static AuditEventCursor of(AuditEvent event) {
    return new AuditEventCursor(event.timestamp(), event.id());
  }

  public static AuditEventCursor decode(String encoded) {
    if (encoded == null || encoded.isBlank()) {
      throw new IllegalArgumentException("cursor has invalid format");
    }

    try {
      String decoded = new String(DECODER.decode(encoded), StandardCharsets.UTF_8);
      String[] parts = decoded.split("\\|", -1);
      if (parts.length != 2) {
        throw new IllegalArgumentException("cursor has invalid format");
      }
      return new AuditEventCursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
    } catch (RuntimeException ex) {
      throw new IllegalArgumentException("cursor has invalid format");
    }
  }

  public String encode() {
    String payload = occurredAt + "|" + id;
    return ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
  }
}
