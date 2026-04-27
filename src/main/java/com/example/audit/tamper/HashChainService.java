package com.example.audit.tamper;

import com.example.audit.domain.AuditEvent;
import com.example.audit.domain.AuditOutcome;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * SHA-256 hash chain over the canonical event payload + previous hash. Hash is computed at write
 * time (AGENTS.md rule 11) and chains preserve order.
 *
 * <p>Canonical context JSON has recursively sorted keys so that PostgreSQL JSONB round-trip (which
 * does not preserve insertion order) cannot break verification.
 */
@Service
public class HashChainService {

  private final ObjectMapper objectMapper;

  public HashChainService(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public String computeHash(
      String prevHash,
      UUID id,
      Instant timestamp,
      String actor,
      String action,
      String resource,
      AuditOutcome outcome,
      String canonicalContextJson) {
    String canonical =
        String.join(
            "\n",
            nullSafe(prevHash),
            id.toString(),
            timestamp.getEpochSecond() + "." + timestamp.getNano(),
            actor,
            action,
            resource,
            outcome.name(),
            canonicalContextJson == null ? "" : canonicalContextJson);
    return sha256(canonical);
  }

  public String canonicalize(JsonNode node) {
    if (node == null || node.isNull()) {
      return "null";
    }
    try {
      return objectMapper.writeValueAsString(sortKeys(node));
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("invalid context json", e);
    }
  }

  public boolean verifyChain(List<AuditEvent> ascendingBySequence) {
    String expectedPrev = null;
    for (AuditEvent e : ascendingBySequence) {
      if (!Objects.equals(expectedPrev, e.prevHash())) {
        return false;
      }
      String recomputed =
          computeHash(
              e.prevHash(),
              e.id(),
              e.timestamp(),
              e.actor(),
              e.action(),
              e.resource(),
              e.outcome(),
              canonicalize(e.context()));
      if (!recomputed.equals(e.hash())) {
        return false;
      }
      expectedPrev = e.hash();
    }
    return true;
  }

  private static JsonNode sortKeys(JsonNode node) {
    if (node.isObject()) {
      TreeMap<String, JsonNode> sorted = new TreeMap<>();
      node.fields()
          .forEachRemaining(entry -> sorted.put(entry.getKey(), sortKeys(entry.getValue())));
      ObjectNode out = JsonNodeFactory.instance.objectNode();
      sorted.forEach(out::set);
      return out;
    }
    if (node.isArray()) {
      ArrayNode out = JsonNodeFactory.instance.arrayNode();
      node.forEach(child -> out.add(sortKeys(child)));
      return out;
    }
    return node;
  }

  private static String nullSafe(String s) {
    return s == null ? "" : s;
  }

  private static String sha256(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
