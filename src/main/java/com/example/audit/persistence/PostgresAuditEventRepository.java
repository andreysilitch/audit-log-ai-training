package com.example.audit.persistence;

import com.example.audit.domain.AuditEvent;
import com.example.audit.domain.AuditEventRepository;
import com.example.audit.domain.AuditEventSearchCriteria;
import com.example.audit.domain.AuditOutcome;
import com.example.audit.domain.NewAuditEvent;
import com.example.audit.tamper.HashChainService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PostgresAuditEventRepository implements AuditEventRepository {

    private static final long ADVISORY_LOCK_KEY = 0x4155444954L; // "AUDIT"

    private static final String SELECT_COLUMNS =
            "id, timestamp, actor, action, resource, outcome, context, prev_hash, hash, sequence_no";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final HashChainService hashChainService;

    public PostgresAuditEventRepository(JdbcTemplate jdbc,
                                        ObjectMapper objectMapper,
                                        HashChainService hashChainService) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.hashChainService = hashChainService;
    }

    @Override
    @Transactional
    public AuditEvent append(NewAuditEvent newEvent) {
        // Serialize chain head reads via tx-scoped advisory lock so prev_hash is consistent.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(?)", ADVISORY_LOCK_KEY);

        Optional<AuditEvent> previous = latest();
        String prevHash = previous.map(AuditEvent::hash).orElse(null);

        UUID id = UUID.randomUUID();
        String contextJson = serialize(newEvent.context());
        String hash = hashChainService.computeHash(
                prevHash,
                id,
                newEvent.timestamp(),
                newEvent.actor(),
                newEvent.action(),
                newEvent.resource(),
                newEvent.outcome(),
                contextJson
        );

        Long sequenceNo = jdbc.queryForObject(
                "INSERT INTO audit_events " +
                        "(id, timestamp, actor, action, resource, outcome, context, prev_hash, hash) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?) " +
                        "RETURNING sequence_no",
                Long.class,
                id,
                Timestamp.from(newEvent.timestamp()),
                newEvent.actor(),
                newEvent.action(),
                newEvent.resource(),
                newEvent.outcome().name(),
                contextJson,
                prevHash,
                hash
        );

        return new AuditEvent(
                id,
                newEvent.timestamp(),
                newEvent.actor(),
                newEvent.action(),
                newEvent.resource(),
                newEvent.outcome(),
                newEvent.context(),
                prevHash,
                hash,
                sequenceNo == null ? 0L : sequenceNo
        );
    }

    @Override
    public List<AuditEvent> search(AuditEventSearchCriteria c) {
        StringBuilder sql = new StringBuilder(
                "SELECT " + SELECT_COLUMNS + " FROM audit_events " +
                        "WHERE timestamp >= ? AND timestamp < ?");
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.from(c.from()));
        args.add(Timestamp.from(c.to()));

        if (c.actor() != null && !c.actor().isBlank()) {
            sql.append(" AND actor = ?");
            args.add(c.actor());
        }
        if (c.resource() != null && !c.resource().isBlank()) {
            sql.append(" AND resource = ?");
            args.add(c.resource());
        }
        sql.append(" ORDER BY timestamp DESC, sequence_no DESC LIMIT ? OFFSET ?");
        args.add(c.limit());
        args.add(c.offset());

        return jdbc.query(sql.toString(), rowMapper(), args.toArray());
    }

    @Override
    public Optional<AuditEvent> latest() {
        List<AuditEvent> rows = jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM audit_events " +
                        "ORDER BY sequence_no DESC LIMIT 1",
                rowMapper()
        );
        return rows.stream().findFirst();
    }

    @Override
    public List<AuditEvent> findOlderThan(Instant cutoff, int limit) {
        return jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM audit_events " +
                        "WHERE timestamp < ? ORDER BY timestamp ASC, sequence_no ASC LIMIT ?",
                rowMapper(),
                Timestamp.from(cutoff),
                limit
        );
    }

    private RowMapper<AuditEvent> rowMapper() {
        return (rs, rowNum) -> {
            JsonNode ctx;
            try {
                String raw = rs.getString("context");
                ctx = raw == null ? objectMapper.createObjectNode() : objectMapper.readTree(raw);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("invalid stored context json", e);
            }
            return new AuditEvent(
                    UUID.fromString(rs.getString("id")),
                    rs.getTimestamp("timestamp").toInstant(),
                    rs.getString("actor"),
                    rs.getString("action"),
                    rs.getString("resource"),
                    AuditOutcome.valueOf(rs.getString("outcome")),
                    ctx,
                    rs.getString("prev_hash"),
                    rs.getString("hash"),
                    rs.getLong("sequence_no")
            );
        };
    }

    private String serialize(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid context json", e);
        }
    }
}
