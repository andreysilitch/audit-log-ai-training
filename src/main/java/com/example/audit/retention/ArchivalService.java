package com.example.audit.retention;

import com.example.audit.domain.AuditEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ArchivalService {

  private static final Logger log = LoggerFactory.getLogger(ArchivalService.class);

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public ArchivalService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public int archive(List<AuditEvent> events) {
    int archived = 0;
    for (AuditEvent e : events) {
      try {
        int rows =
            jdbc.update(
                "INSERT INTO audit_events_archive "
                    + "(id, timestamp, actor, action, resource, outcome, context, prev_hash, hash, sequence_no) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?) "
                    + "ON CONFLICT (id) DO NOTHING",
                e.id(),
                Timestamp.from(e.timestamp()),
                e.actor(),
                e.action(),
                e.resource(),
                e.outcome().name(),
                objectMapper.writeValueAsString(e.context()),
                e.prevHash(),
                e.hash(),
                e.sequenceNo());
        archived += rows;
      } catch (JsonProcessingException ex) {
        log.error("Failed to serialize context for archival id={}", e.id(), ex);
      } catch (RuntimeException ex) {
        log.error("Failed to archive event id={}", e.id(), ex);
      }
    }
    return archived;
  }
}
