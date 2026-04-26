package com.example.audit;

import com.example.audit.domain.AuditEventRepository;
import com.example.audit.domain.AuditOutcome;
import com.example.audit.domain.NewAuditEvent;
import com.example.audit.retention.ArchivalService;
import com.example.audit.retention.RetentionPolicyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "audit.retention.days=30")
class RetentionPolicyIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired AuditEventRepository repository;
    @Autowired ArchivalService archivalService;
    @Autowired Clock clock;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void archivesEventsOlderThanRetentionWindow() {
        String oldActor = "old-" + UUID.randomUUID();
        String newActor = "new-" + UUID.randomUUID();
        Instant longAgo = Instant.now().minus(60, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
        Instant recent  = Instant.now().minus(5,  ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);

        repository.append(new NewAuditEvent(longAgo,                 oldActor, "a", "r", AuditOutcome.SUCCESS, objectMapper.createObjectNode()));
        repository.append(new NewAuditEvent(longAgo.plusSeconds(1),  oldActor, "a", "r", AuditOutcome.DENIED,  objectMapper.createObjectNode()));
        repository.append(new NewAuditEvent(recent,                  newActor, "a", "r", AuditOutcome.SUCCESS, objectMapper.createObjectNode()));

        var retention = new RetentionPolicyService(repository, archivalService, clock, 30);
        retention.run();

        Long archivedOld = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events_archive WHERE actor = ?", Long.class, oldActor);
        Long archivedNew = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events_archive WHERE actor = ?", Long.class, newActor);
        Long mainOld = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE actor = ?", Long.class, oldActor);

        assertThat(archivedOld).isEqualTo(2L);
        assertThat(archivedNew).isEqualTo(0L);
        // Append-only invariant: main table still holds the originals.
        assertThat(mainOld).isEqualTo(2L);
    }

    @Test
    void archivalIsIdempotent() {
        String actor = "idem-" + UUID.randomUUID();
        Instant longAgo = Instant.now().minus(90, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
        repository.append(new NewAuditEvent(longAgo, actor, "a", "r", AuditOutcome.SUCCESS, objectMapper.createObjectNode()));

        var retention = new RetentionPolicyService(repository, archivalService, clock, 30);
        retention.run();
        retention.run();

        Long archived = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events_archive WHERE actor = ?", Long.class, actor);
        assertThat(archived).isEqualTo(1L);
    }

    @Test
    void noopWhenNothingExpired() {
        String actor = "fresh-" + UUID.randomUUID();
        repository.append(new NewAuditEvent(
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                actor, "a", "r", AuditOutcome.SUCCESS, objectMapper.createObjectNode()));

        var retention = new RetentionPolicyService(repository, archivalService, clock, 30);
        retention.run();

        Long archived = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events_archive WHERE actor = ?", Long.class, actor);
        assertThat(archived).isEqualTo(0L);
    }
}
