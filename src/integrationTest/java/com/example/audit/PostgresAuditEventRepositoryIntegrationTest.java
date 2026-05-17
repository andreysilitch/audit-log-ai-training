package com.example.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.audit.domain.AuditEvent;
import com.example.audit.domain.AuditEventCursor;
import com.example.audit.domain.AuditEventRepository;
import com.example.audit.domain.AuditEventSearchCriteria;
import com.example.audit.domain.AuditOutcome;
import com.example.audit.domain.NewAuditEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class PostgresAuditEventRepositoryIntegrationTest {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired AuditEventRepository repository;
  @Autowired ObjectMapper objectMapper;

  @Test
  void paginatesResultsByLimitAndCursor() {
    String actor = "pager-" + UUID.randomUUID();
    Instant base = Instant.now().truncatedTo(ChronoUnit.MICROS);
    for (int i = 0; i < 5; i++) {
      repository.append(
          new NewAuditEvent(
              base.plusSeconds(i),
              actor,
              "act",
              "res:" + i,
              AuditOutcome.SUCCESS,
              objectMapper.createObjectNode()));
    }
    // Repository fetches limit + 1 so the service can detect hasMore.
    var criteria =
        new AuditEventSearchCriteria(
            actor, null, base.minusSeconds(1), base.plusSeconds(60), 2, null);

    List<AuditEvent> page1 = repository.search(criteria);
    AuditEventCursor secondPageCursor = AuditEventCursor.of(page1.get(1));
    List<AuditEvent> page2 =
        repository.search(
            new AuditEventSearchCriteria(
                actor, null, base.minusSeconds(1), base.plusSeconds(60), 2, secondPageCursor));
    AuditEventCursor thirdPageCursor = AuditEventCursor.of(page2.get(1));
    List<AuditEvent> page3 =
        repository.search(
            new AuditEventSearchCriteria(
                actor, null, base.minusSeconds(1), base.plusSeconds(60), 2, thirdPageCursor));

    assertThat(page1).hasSize(3);
    assertThat(page2).hasSize(3);
    assertThat(page3).hasSize(1);
    assertThat(page2.stream().map(AuditEvent::id).toList())
        .doesNotContain(page1.get(0).id(), page1.get(1).id());
    assertThat(page3.stream().map(AuditEvent::id).toList())
        .doesNotContain(page2.get(0).id(), page2.get(1).id());

    // DESC ordering by timestamp.
    assertThat(page1.get(0).timestamp()).isAfterOrEqualTo(page1.get(1).timestamp());
  }

  @Test
  void deterministicTieBreakOnIdenticalTimestamp() {
    String actor = "tie-" + UUID.randomUUID();
    Instant sameInstant = Instant.now().truncatedTo(ChronoUnit.MICROS);

    repository.append(
        new NewAuditEvent(
            sameInstant, actor, "a", "r", AuditOutcome.SUCCESS, objectMapper.createObjectNode()));
    repository.append(
        new NewAuditEvent(
            sameInstant, actor, "a", "r", AuditOutcome.SUCCESS, objectMapper.createObjectNode()));
    repository.append(
        new NewAuditEvent(
            sameInstant, actor, "a", "r", AuditOutcome.SUCCESS, objectMapper.createObjectNode()));

    var criteria =
        new AuditEventSearchCriteria(
            actor, null, sameInstant.minusSeconds(1), sameInstant.plusSeconds(1), 10, null);

    List<AuditEvent> first = repository.search(criteria);
    List<AuditEvent> second = repository.search(criteria);

    assertThat(first).hasSize(3).allMatch(e -> e.timestamp().equals(sameInstant));
    // Tie-break is id DESC: every adjacent pair must be in descending id order.
    for (int i = 1; i < first.size(); i++) {
      assertThat(first.get(i).id().toString()).isLessThan(first.get(i - 1).id().toString());
    }
    // Repeating the query yields the same sequence.
    assertThat(second.stream().map(AuditEvent::id).toList())
        .isEqualTo(first.stream().map(AuditEvent::id).toList());
  }

  @Test
  void searchFetchesLimitPlusOneForHasMoreDetection() {
    String actor = "overflow-" + UUID.randomUUID();
    Instant base = Instant.now().truncatedTo(ChronoUnit.MICROS);
    for (int i = 0; i < 6; i++) {
      repository.append(
          new NewAuditEvent(
              base.plusSeconds(i),
              actor,
              "a",
              "r",
              AuditOutcome.SUCCESS,
              objectMapper.createObjectNode()));
    }

    List<AuditEvent> rows =
        repository.search(
            new AuditEventSearchCriteria(
                actor, null, base.minusSeconds(1), base.plusSeconds(60), 5, null));

    assertThat(rows).hasSize(6);
  }

  @Test
  void timeRangeIsHalfOpen() {
    String actor = "range-" + UUID.randomUUID();
    Instant t0 = Instant.now().truncatedTo(ChronoUnit.MICROS);
    repository.append(
        new NewAuditEvent(
            t0, actor, "a", "r", AuditOutcome.SUCCESS, objectMapper.createObjectNode()));
    repository.append(
        new NewAuditEvent(
            t0.plusSeconds(10),
            actor,
            "a",
            "r",
            AuditOutcome.SUCCESS,
            objectMapper.createObjectNode()));
    repository.append(
        new NewAuditEvent(
            t0.plusSeconds(20),
            actor,
            "a",
            "r",
            AuditOutcome.SUCCESS,
            objectMapper.createObjectNode()));

    // [t0, t0+20) — must include t0 and t0+10 but exclude t0+20
    List<AuditEvent> result =
        repository.search(
            new AuditEventSearchCriteria(actor, null, t0, t0.plusSeconds(20), 100, null));

    assertThat(result).hasSize(2);
    assertThat(result).allMatch(e -> e.timestamp().isBefore(t0.plusSeconds(20)));
  }

  @Test
  void filtersByResource() {
    String actor = "res-" + UUID.randomUUID();
    Instant t0 = Instant.now().truncatedTo(ChronoUnit.MICROS);
    repository.append(
        new NewAuditEvent(
            t0, actor, "a", "project:1", AuditOutcome.SUCCESS, objectMapper.createObjectNode()));
    repository.append(
        new NewAuditEvent(
            t0.plusSeconds(1),
            actor,
            "a",
            "project:2",
            AuditOutcome.SUCCESS,
            objectMapper.createObjectNode()));
    repository.append(
        new NewAuditEvent(
            t0.plusSeconds(2),
            actor,
            "a",
            "project:1",
            AuditOutcome.SUCCESS,
            objectMapper.createObjectNode()));

    List<AuditEvent> result =
        repository.search(
            new AuditEventSearchCriteria(
                actor, "project:1", t0.minusSeconds(1), t0.plusSeconds(60), 100, null));

    assertThat(result).hasSize(2).allMatch(e -> e.resource().equals("project:1"));
  }

  @Test
  void latestReturnsHighestSequenceNo() {
    Instant t0 = Instant.now().truncatedTo(ChronoUnit.MICROS);
    AuditEvent first =
        repository.append(
            new NewAuditEvent(
                t0,
                "latest-actor",
                "a",
                "r",
                AuditOutcome.SUCCESS,
                objectMapper.createObjectNode()));
    AuditEvent second =
        repository.append(
            new NewAuditEvent(
                t0.plusSeconds(1),
                "latest-actor",
                "a",
                "r",
                AuditOutcome.SUCCESS,
                objectMapper.createObjectNode()));

    AuditEvent latest = repository.latest().orElseThrow();
    assertThat(latest.sequenceNo()).isGreaterThanOrEqualTo(second.sequenceNo());
    assertThat(latest.sequenceNo()).isGreaterThan(first.sequenceNo());
  }

  @Test
  void findOlderThanReturnsAscendingByTimestamp() {
    String actor = "old-" + UUID.randomUUID();
    Instant ancient = Instant.now().minus(60, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
    repository.append(
        new NewAuditEvent(
            ancient.plusSeconds(2),
            actor,
            "a",
            "r",
            AuditOutcome.SUCCESS,
            objectMapper.createObjectNode()));
    repository.append(
        new NewAuditEvent(
            ancient, actor, "a", "r", AuditOutcome.SUCCESS, objectMapper.createObjectNode()));
    repository.append(
        new NewAuditEvent(
            ancient.plusSeconds(1),
            actor,
            "a",
            "r",
            AuditOutcome.SUCCESS,
            objectMapper.createObjectNode()));

    List<AuditEvent> older =
        repository.findOlderThan(Instant.now().minus(30, ChronoUnit.DAYS), 100).stream()
            .filter(e -> e.actor().equals(actor))
            .toList();

    assertThat(older).hasSize(3);
    for (int i = 1; i < older.size(); i++) {
      assertThat(older.get(i).timestamp()).isAfterOrEqualTo(older.get(i - 1).timestamp());
    }
  }
}
