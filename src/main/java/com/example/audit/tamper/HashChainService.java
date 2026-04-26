package com.example.audit.tamper;

import com.example.audit.domain.AuditEvent;
import com.example.audit.domain.AuditOutcome;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * SHA-256 hash chain over the canonical event payload + previous hash.
 * Hash is computed at write time (AGENTS.md rule 11) and chains preserve order.
 */
@Service
public class HashChainService {

    private final ObjectMapper objectMapper;

    public HashChainService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String computeHash(String prevHash,
                              UUID id,
                              Instant timestamp,
                              String actor,
                              String action,
                              String resource,
                              AuditOutcome outcome,
                              String contextJson) {
        String canonical = String.join("\n",
                nullSafe(prevHash),
                id.toString(),
                timestamp.getEpochSecond() + "." + timestamp.getNano(),
                actor,
                action,
                resource,
                outcome.name(),
                contextJson == null ? "" : contextJson
        );
        return sha256(canonical);
    }

    /**
     * Verifies an ascending-by-sequence list of events against their stored hashes.
     */
    public boolean verifyChain(List<AuditEvent> ascendingBySequence) {
        String expectedPrev = null;
        for (AuditEvent e : ascendingBySequence) {
            if (!Objects.equals(expectedPrev, e.prevHash())) {
                return false;
            }
            String contextJson;
            try {
                contextJson = e.context() == null ? "{}" : objectMapper.writeValueAsString(e.context());
            } catch (JsonProcessingException ex) {
                return false;
            }
            String recomputed = computeHash(
                    e.prevHash(),
                    e.id(),
                    e.timestamp(),
                    e.actor(),
                    e.action(),
                    e.resource(),
                    e.outcome(),
                    contextJson
            );
            if (!recomputed.equals(e.hash())) {
                return false;
            }
            expectedPrev = e.hash();
        }
        return true;
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
