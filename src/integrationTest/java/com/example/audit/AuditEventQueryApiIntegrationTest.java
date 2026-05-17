package com.example.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.audit.domain.AuditEventService;
import com.example.audit.domain.AuditOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(AuditEventQueryApiIntegrationTest.TestClockConfig.class)
class AuditEventQueryApiIntegrationTest {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired MockMvc mvc;
  @Autowired AuditEventService service;
  @Autowired ObjectMapper objectMapper;
  @Autowired TestClock clock;

  private static String windowFrom() {
    return Instant.parse("2026-01-01T00:00:00Z").toString();
  }

  private static String windowTo() {
    return Instant.parse("2027-01-01T00:00:00Z").toString();
  }

  private void seed(String actor, String resource, AuditOutcome outcome, Instant at) {
    clock.setNow(at);
    service.record(actor, "act", resource, outcome, null);
  }

  @Test
  void searchesByActorAndTimeRange() throws Exception {
    String actor = "alice-" + UUID.randomUUID();
    String otherActor = "bob-" + UUID.randomUUID();
    Instant base = Instant.parse("2026-06-01T10:00:00Z");
    seed(actor, "r1", AuditOutcome.SUCCESS, base);
    seed(actor, "r1", AuditOutcome.SUCCESS, base.plusSeconds(1));
    seed(actor, "r1", AuditOutcome.SUCCESS, base.plusSeconds(2));
    seed(otherActor, "r1", AuditOutcome.SUCCESS, base.plusSeconds(3));
    seed(otherActor, "r1", AuditOutcome.SUCCESS, base.plusSeconds(4));

    mvc.perform(
            get("/audit-events")
                .param("actor", actor)
                .param("from", windowFrom())
                .param("to", windowTo()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(3))
        .andExpect(
            jsonPath("$.items[*].actor")
                .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.equalTo(actor))));
  }

  @Test
  void searchesByMultipleActorsAndTimeRange() throws Exception {
    String actor1 = "multi-a-" + UUID.randomUUID();
    String actor2 = "multi-b-" + UUID.randomUUID();
    String actor3 = "multi-c-" + UUID.randomUUID();
    Instant base = Instant.parse("2026-06-01T12:00:00Z");
    seed(actor1, "r1", AuditOutcome.SUCCESS, base);
    seed(actor2, "r1", AuditOutcome.SUCCESS, base.plusSeconds(1));
    seed(actor3, "r1", AuditOutcome.SUCCESS, base.plusSeconds(2));

    mvc.perform(
            get("/audit-events")
                .param("actor", actor1 + "," + actor3)
                .param("from", windowFrom())
                .param("to", windowTo()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].actor").value(actor3))
        .andExpect(jsonPath("$.items[1].actor").value(actor1));
  }

  @Test
  void searchesByResourceAndTimeRange() throws Exception {
    String actor = "ann-" + UUID.randomUUID();
    String resource = "order/" + UUID.randomUUID();
    String otherResource = "order/" + UUID.randomUUID();
    Instant base = Instant.parse("2026-06-02T10:00:00Z");
    seed(actor, resource, AuditOutcome.SUCCESS, base);
    seed(actor, resource, AuditOutcome.SUCCESS, base.plusSeconds(1));
    seed(actor, otherResource, AuditOutcome.SUCCESS, base.plusSeconds(2));

    mvc.perform(
            get("/audit-events")
                .param("resource", resource)
                .param("from", windowFrom())
                .param("to", windowTo()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(
            jsonPath("$.items[*].resource")
                .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.equalTo(resource))));
  }

  @Test
  void combinesActorAndResource() throws Exception {
    String actor = "carol-" + UUID.randomUUID();
    String otherActor = "dan-" + UUID.randomUUID();
    String resource = "doc/" + UUID.randomUUID();
    String otherResource = "doc/" + UUID.randomUUID();
    Instant base = Instant.parse("2026-06-03T10:00:00Z");
    seed(actor, resource, AuditOutcome.SUCCESS, base);
    seed(actor, otherResource, AuditOutcome.SUCCESS, base.plusSeconds(1));
    seed(otherActor, resource, AuditOutcome.SUCCESS, base.plusSeconds(2));

    mvc.perform(
            get("/audit-events")
                .param("actor", actor)
                .param("resource", resource)
                .param("from", windowFrom())
                .param("to", windowTo()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].actor").value(actor))
        .andExpect(jsonPath("$.items[0].resource").value(resource));
  }

  @Test
  void combinesMultipleActorsAndResource() throws Exception {
    String actor1 = "carol-a-" + UUID.randomUUID();
    String actor2 = "carol-b-" + UUID.randomUUID();
    String otherActor = "carol-c-" + UUID.randomUUID();
    String resource = "doc/" + UUID.randomUUID();
    String otherResource = "doc/" + UUID.randomUUID();
    Instant base = Instant.parse("2026-06-03T12:00:00Z");
    seed(actor1, resource, AuditOutcome.SUCCESS, base);
    seed(actor2, resource, AuditOutcome.SUCCESS, base.plusSeconds(1));
    seed(actor1, otherResource, AuditOutcome.SUCCESS, base.plusSeconds(2));
    seed(otherActor, resource, AuditOutcome.SUCCESS, base.plusSeconds(3));

    mvc.perform(
            get("/audit-events")
                .param("actor", actor1 + "," + actor2)
                .param("resource", resource)
                .param("from", windowFrom())
                .param("to", windowTo()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].actor").value(actor2))
        .andExpect(jsonPath("$.items[0].resource").value(resource))
        .andExpect(jsonPath("$.items[1].actor").value(actor1))
        .andExpect(jsonPath("$.items[1].resource").value(resource));
  }

  @Test
  void paginatesAcrossPages() throws Exception {
    String actor = "page-" + UUID.randomUUID();
    Instant base = Instant.parse("2026-06-04T10:00:00Z");
    for (int i = 0; i < 7; i++) {
      seed(actor, "r", AuditOutcome.SUCCESS, base.plusSeconds(i));
    }

    java.util.Set<String> seenIds = new java.util.LinkedHashSet<>();
    String cursor = null;
    boolean hasMore = true;
    int loops = 0;
    while (hasMore) {
      var request =
          get("/audit-events")
              .param("actor", actor)
              .param("from", windowFrom())
              .param("to", windowTo())
              .param("limit", "3");
      if (cursor != null) {
        request = request.param("cursor", cursor);
      }
      MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
      JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
      JsonNode items = body.get("items");
      for (JsonNode item : items) {
        boolean fresh = seenIds.add(item.get("id").asText());
        assertThat(fresh).as("no duplicate id across pages").isTrue();
      }
      JsonNode current = body.get("page").get("cursor");
      if (cursor == null) {
        assertThat(current.isNull()).isTrue();
      } else {
        assertThat(current.asText()).isEqualTo(cursor);
      }
      hasMore = body.get("page").get("hasMore").asBoolean();
      JsonNode next = body.get("page").get("nextCursor");
      if (hasMore) {
        assertThat(next.isNull()).isFalse();
        cursor = next.asText();
      } else {
        assertThat(next.isNull()).isTrue();
      }
      assertThat(++loops).isLessThan(10);
    }

    assertThat(seenIds).hasSize(7);
  }

  @Test
  void paginatesAcrossPagesForMultipleActors() throws Exception {
    String actor1 = "page-a-" + UUID.randomUUID();
    String actor2 = "page-b-" + UUID.randomUUID();
    Instant base = Instant.parse("2026-06-04T12:00:00Z");
    for (int i = 0; i < 4; i++) {
      seed(actor1, "r-a-" + i, AuditOutcome.SUCCESS, base.plusSeconds(i));
      seed(actor2, "r-b-" + i, AuditOutcome.SUCCESS, base.plusSeconds(i + 4));
    }

    java.util.Set<String> seenIds = new java.util.LinkedHashSet<>();
    String cursor = null;
    boolean hasMore = true;
    int loops = 0;
    String actorFilter = actor1 + "," + actor2;
    while (hasMore) {
      var request =
          get("/audit-events")
              .param("actor", actorFilter)
              .param("from", windowFrom())
              .param("to", windowTo())
              .param("limit", "3");
      if (cursor != null) {
        request = request.param("cursor", cursor);
      }
      MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
      JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
      for (JsonNode item : body.get("items")) {
        boolean fresh = seenIds.add(item.get("id").asText());
        assertThat(fresh).as("no duplicate id across multi-actor pages").isTrue();
      }
      hasMore = body.get("page").get("hasMore").asBoolean();
      JsonNode next = body.get("page").get("nextCursor");
      if (hasMore) {
        assertThat(next.isNull()).isFalse();
        cursor = next.asText();
      } else {
        assertThat(next.isNull()).isTrue();
      }
      assertThat(++loops).isLessThan(10);
    }

    assertThat(seenIds).hasSize(8);
  }

  @Test
  void deterministicOrderOnIdenticalTimestamp() throws Exception {
    String actor = "tie-" + UUID.randomUUID();
    Instant fixed = Instant.parse("2026-06-05T10:00:00Z");
    seed(actor, "r", AuditOutcome.SUCCESS, fixed);
    seed(actor, "r", AuditOutcome.SUCCESS, fixed);
    seed(actor, "r", AuditOutcome.SUCCESS, fixed);

    MvcResult first =
        mvc.perform(
                get("/audit-events")
                    .param("actor", actor)
                    .param("from", windowFrom())
                    .param("to", windowTo()))
            .andExpect(status().isOk())
            .andReturn();
    MvcResult second =
        mvc.perform(
                get("/audit-events")
                    .param("actor", actor)
                    .param("from", windowFrom())
                    .param("to", windowTo()))
            .andExpect(status().isOk())
            .andReturn();

    String body1 = first.getResponse().getContentAsString();
    String body2 = second.getResponse().getContentAsString();
    JsonNode items1 = objectMapper.readTree(body1).get("items");
    JsonNode items2 = objectMapper.readTree(body2).get("items");
    assertThat(items1.size()).isEqualTo(3);
    java.util.List<String> ids1 = new java.util.ArrayList<>();
    java.util.List<String> ids2 = new java.util.ArrayList<>();
    items1.forEach(n -> ids1.add(n.get("id").asText()));
    items2.forEach(n -> ids2.add(n.get("id").asText()));
    assertThat(ids2).isEqualTo(ids1);
    for (int i = 1; i < ids1.size(); i++) {
      assertThat(ids1.get(i)).isLessThan(ids1.get(i - 1));
    }
  }

  @Test
  void emptyResultReturnsOkWithEmptyItems() throws Exception {
    String actor = "nobody-" + UUID.randomUUID();
    mvc.perform(
            get("/audit-events")
                .param("actor", actor)
                .param("from", windowFrom())
                .param("to", windowTo()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0))
        .andExpect(jsonPath("$.page.hasMore").value(false))
        .andExpect(jsonPath("$.page.cursor").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.page.nextCursor").value(org.hamcrest.Matchers.nullValue()));
  }

  @Test
  void missingActorAndResourceReturns422() throws Exception {
    mvc.perform(get("/audit-events").param("from", windowFrom()).param("to", windowTo()))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error").value("validation_failed"))
        .andExpect(
            jsonPath("$.message").value(org.hamcrest.Matchers.containsString("actor or resource")));
  }

  @Test
  void fromAfterToReturns422() throws Exception {
    mvc.perform(
            get("/audit-events")
                .param("actor", "alice")
                .param("from", "2026-12-01T00:00:00Z")
                .param("to", "2026-01-01T00:00:00Z"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error").value("validation_failed"))
        .andExpect(
            jsonPath("$.message")
                .value(org.hamcrest.Matchers.containsString("from must be before to")));
  }

  @Test
  void malformedTimestampReturns400() throws Exception {
    mvc.perform(
            get("/audit-events")
                .param("actor", "alice")
                .param("from", "not-a-date")
                .param("to", windowTo()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void limitBelowOneReturns422() throws Exception {
    mvc.perform(
            get("/audit-events")
                .param("actor", "alice")
                .param("from", windowFrom())
                .param("to", windowTo())
                .param("limit", "0"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.message")
                .value(org.hamcrest.Matchers.containsString("limit must be between 1 and 200")));
  }

  @Test
  void limitAboveMaxReturns422() throws Exception {
    mvc.perform(
            get("/audit-events")
                .param("actor", "alice")
                .param("from", windowFrom())
                .param("to", windowTo())
                .param("limit", "201"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.message")
                .value(org.hamcrest.Matchers.containsString("limit must be between 1 and 200")));
  }

  @Test
  void malformedCursorReturns400() throws Exception {
    mvc.perform(
            get("/audit-events")
                .param("actor", "alice")
                .param("from", windowFrom())
                .param("to", windowTo())
                .param("cursor", "not-a-valid-cursor"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("cursor has invalid format"));
  }

  @Test
  void actorListAboveMaxReturns422() throws Exception {
    String actors =
        String.join(
            ",", java.util.stream.IntStream.range(0, 11).mapToObj(i -> "actor-" + i).toList());

    mvc.perform(
            get("/audit-events")
                .param("actor", actors)
                .param("from", windowFrom())
                .param("to", windowTo()))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error").value("validation_failed"))
        .andExpect(
            jsonPath("$.message").value(org.hamcrest.Matchers.containsString("at most 10 values")));
  }

  @Test
  void defaultLimitIsFifty() throws Exception {
    mvc.perform(
            get("/audit-events")
                .param("actor", "alice")
                .param("from", windowFrom())
                .param("to", windowTo()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.limit").value(50))
        .andExpect(jsonPath("$.page.cursor").value(org.hamcrest.Matchers.nullValue()));
  }

  @Test
  void searchResponseHidesHashAndSequenceNo() throws Exception {
    String actor = "leak-" + UUID.randomUUID();
    seed(actor, "r", AuditOutcome.SUCCESS, Instant.parse("2026-06-06T10:00:00Z"));

    mvc.perform(
            get("/audit-events")
                .param("actor", actor)
                .param("from", windowFrom())
                .param("to", windowTo()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").exists())
        .andExpect(jsonPath("$.items[0].occurredAt").exists())
        .andExpect(jsonPath("$.items[0].hash").doesNotExist())
        .andExpect(jsonPath("$.items[0].sequenceNo").doesNotExist())
        .andExpect(jsonPath("$.items[0].prevHash").doesNotExist())
        .andExpect(jsonPath("$.items[0].timestamp").doesNotExist());
  }

  static class TestClock extends Clock {
    private volatile Instant now = Instant.parse("2026-01-01T00:00:00Z");

    void setNow(Instant instant) {
      this.now = instant;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @TestConfiguration
  static class TestClockConfig {
    @Bean
    @Primary
    TestClock testClock() {
      return new TestClock();
    }
  }
}
