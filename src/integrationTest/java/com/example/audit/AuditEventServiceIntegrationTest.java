package com.example.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.audit.domain.AuditEvent;
import com.example.audit.domain.AuditEventRepository;
import com.example.audit.domain.AuditEventSearchCriteria;
import com.example.audit.domain.AuditEventService;
import com.example.audit.domain.AuditOutcome;
import com.example.audit.tamper.HashChainService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class AuditEventServiceIntegrationTest {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired AuditEventService service;
  @Autowired AuditEventRepository repository;
  @Autowired HashChainService hashChainService;
  @Autowired ObjectMapper objectMapper;
  @Autowired JdbcTemplate jdbc;

  @Test
  void appendsEventAndChainsHash() throws Exception {
    var ctx = objectMapper.readTree("{\"k\":1}");
    AuditEvent first = service.record("alice", "user.login", "user:1", AuditOutcome.SUCCESS, ctx);
    AuditEvent second = service.record("alice", "user.logout", "user:1", AuditOutcome.SUCCESS, ctx);

    assertThat(first.hash()).isNotBlank();
    assertThat(second.hash()).isNotBlank();
    assertThat(second.prevHash()).isEqualTo(first.hash());
    assertThat(second.sequenceNo()).isGreaterThan(first.sequenceNo());
  }

  @Test
  void searchesByActorAndTimeRange() {
    Instant before = Instant.now().minusSeconds(1);
    service.record("bob", "x.do", "x:1", AuditOutcome.SUCCESS, null);
    service.record("carol", "x.do", "x:1", AuditOutcome.SUCCESS, null);
    service.record("bob", "x.fail", "x:1", AuditOutcome.ERROR, null);
    Instant after = Instant.now().plusSeconds(1);

    List<AuditEvent> bobOnly =
        service.search(new AuditEventSearchCriteria("bob", null, before, after, 50, 0));

    assertThat(bobOnly).hasSize(2).allMatch(e -> e.actor().equals("bob"));
  }

  @Test
  void rejectsBlankActor() {
    assertThatThrownBy(() -> service.record(" ", "x", "y", AuditOutcome.SUCCESS, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("actor is required");
  }

  @Test
  void rejectsSearchWithoutTimeRange() {
    assertThatThrownBy(
            () -> service.search(new AuditEventSearchCriteria(null, null, null, null, 100, 0)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("time range is required");
  }

  @Test
  void databaseRejectsUpdateAndDelete() {
    service.record("dave", "act", "res", AuditOutcome.SUCCESS, null);

    assertThatThrownBy(() -> jdbc.update("UPDATE audit_events SET actor = 'mallory'"))
        .hasMessageContaining("append-only");
    assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_events"))
        .hasMessageContaining("append-only");
  }

  @Test
  void hashChainDetectsHistoricalTampering() {
    service.record("eve", "a", "r", AuditOutcome.SUCCESS, null);
    service.record("eve", "b", "r", AuditOutcome.SUCCESS, null);
    service.record("eve", "c", "r", AuditOutcome.SUCCESS, null);

    Instant from = Instant.now().minusSeconds(60);
    Instant to = Instant.now().plusSeconds(60);
    List<AuditEvent> ascending =
        service.search(new AuditEventSearchCriteria("eve", null, from, to, 100, 0)).stream()
            .sorted((x, y) -> Long.compare(x.sequenceNo(), y.sequenceNo()))
            .toList();

    assertThat(hashChainService.verifyChain(ascending)).isTrue();

    // Simulate corruption of the archive copy (main table is trigger-protected).
    // Build a tampered list and re-verify.
    List<AuditEvent> tampered = new java.util.ArrayList<>(ascending);
    AuditEvent middle = tampered.get(1);
    tampered.set(
        1,
        new AuditEvent(
            middle.id(),
            middle.timestamp(),
            middle.actor(),
            "tampered.action",
            middle.resource(),
            middle.outcome(),
            middle.context(),
            middle.prevHash(),
            middle.hash(),
            middle.sequenceNo()));

    assertThat(hashChainService.verifyChain(tampered)).isFalse();
  }
}
