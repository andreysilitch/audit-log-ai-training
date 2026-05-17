package com.example.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuditEventServiceTest {

  private static final Instant T0 = Instant.parse("2026-04-01T00:00:00Z");
  private static final Instant T1 = Instant.parse("2026-05-01T00:00:00Z");

  private final AuditEventRepository repository = mock(AuditEventRepository.class);
  private final Clock clock = Clock.systemUTC();
  private final AuditEventService service = new AuditEventService(repository, clock);

  private AuditEventSearchCriteria criteria(
      String actor, String resource, Instant from, Instant to, int limit, AuditEventCursor cursor) {
    return new AuditEventSearchCriteria(actor, resource, from, to, limit, cursor);
  }

  @Test
  void rejectsNullFrom() {
    assertThatThrownBy(() -> service.search(criteria("alice", null, null, T1, 50, null)))
        .isInstanceOf(QueryValidationException.class)
        .hasMessageContaining("from and to");
  }

  @Test
  void rejectsNullTo() {
    assertThatThrownBy(() -> service.search(criteria("alice", null, T0, null, 50, null)))
        .isInstanceOf(QueryValidationException.class)
        .hasMessageContaining("from and to");
  }

  @Test
  void rejectsFromEqualToTo() {
    assertThatThrownBy(() -> service.search(criteria("alice", null, T0, T0, 50, null)))
        .isInstanceOf(QueryValidationException.class)
        .hasMessageContaining("from must be before to");
  }

  @Test
  void rejectsFromAfterTo() {
    assertThatThrownBy(() -> service.search(criteria("alice", null, T1, T0, 50, null)))
        .isInstanceOf(QueryValidationException.class)
        .hasMessageContaining("from must be before to");
  }

  @Test
  void rejectsBothActorAndResourceAbsent() {
    assertThatThrownBy(() -> service.search(criteria(null, null, T0, T1, 50, null)))
        .isInstanceOf(QueryValidationException.class)
        .hasMessageContaining("at least one of actor or resource");
  }

  @Test
  void rejectsBothActorAndResourceBlank() {
    assertThatThrownBy(() -> service.search(criteria("", "  ", T0, T1, 50, null)))
        .isInstanceOf(QueryValidationException.class)
        .hasMessageContaining("at least one of actor or resource");
  }

  @Test
  void rejectsBlankActorWhenResourceProvided() {
    assertThatThrownBy(() -> service.search(criteria(" ", "order/9", T0, T1, 50, null)))
        .isInstanceOf(QueryValidationException.class)
        .hasMessageContaining("actor must not be blank");
  }

  @Test
  void rejectsBlankResourceWhenActorProvided() {
    assertThatThrownBy(() -> service.search(criteria("alice", "", T0, T1, 50, null)))
        .isInstanceOf(QueryValidationException.class)
        .hasMessageContaining("resource must not be blank");
  }

  @Test
  void rejectsLimitZero() {
    assertThatThrownBy(() -> service.search(criteria("alice", null, T0, T1, 0, null)))
        .isInstanceOf(QueryValidationException.class)
        .hasMessageContaining("limit must be between 1 and 200");
  }

  @Test
  void rejectsLimitAboveMax() {
    assertThatThrownBy(() -> service.search(criteria("alice", null, T0, T1, 201, null)))
        .isInstanceOf(QueryValidationException.class)
        .hasMessageContaining("limit must be between 1 and 200");
  }

  @Test
  void happyPathCallsRepository() {
    AuditEventSearchCriteria c = criteria("alice", null, T0, T1, 50, null);
    when(repository.search(any())).thenReturn(List.of());
    AuditEventPage page = service.search(c);
    assertThat(page.items()).isEmpty();
    assertThat(page.hasMore()).isFalse();
    assertThat(page.cursor()).isNull();
    assertThat(page.nextCursor()).isNull();
    verify(repository, times(1)).search(c);
  }

  @Test
  void emptyRepositoryResultProducesEmptyPage() {
    AuditEventSearchCriteria c = criteria("alice", null, T0, T1, 10, null);
    when(repository.search(any())).thenReturn(List.of());
    AuditEventPage page = service.search(c);
    assertThat(page.items()).isEmpty();
    assertThat(page.hasMore()).isFalse();
    assertThat(page.cursor()).isNull();
    assertThat(page.nextCursor()).isNull();
    assertThat(page.limit()).isEqualTo(10);
  }

  @Test
  void partialPageHasNoMore() {
    AuditEventSearchCriteria c = criteria("alice", null, T0, T1, 10, null);
    when(repository.search(any())).thenReturn(eventList(3));
    AuditEventPage page = service.search(c);
    assertThat(page.items()).hasSize(3);
    assertThat(page.hasMore()).isFalse();
    assertThat(page.nextCursor()).isNull();
  }

  @Test
  void exactPageHasNoMore() {
    AuditEventSearchCriteria c = criteria("alice", null, T0, T1, 10, null);
    when(repository.search(any())).thenReturn(eventList(10));
    AuditEventPage page = service.search(c);
    assertThat(page.items()).hasSize(10);
    assertThat(page.hasMore()).isFalse();
    assertThat(page.nextCursor()).isNull();
  }

  @Test
  void overflowPageTrimsAndSetsNextCursor() {
    AuditEventSearchCriteria c = criteria("alice", null, T0, T1, 10, null);
    when(repository.search(any())).thenReturn(eventList(11));
    AuditEventPage page = service.search(c);
    assertThat(page.items()).hasSize(10);
    assertThat(page.hasMore()).isTrue();
    assertThat(page.nextCursor())
        .isEqualTo(AuditEventCursor.of(page.items().get(page.items().size() - 1)).encode());
    assertThat(page.limit()).isEqualTo(10);
  }

  @Test
  void echoesCurrentCursorInPageMetadata() {
    AuditEventCursor cursor = new AuditEventCursor(T0.plusSeconds(5), java.util.UUID.randomUUID());
    AuditEventSearchCriteria c = criteria("alice", null, T0, T1, 10, cursor);
    when(repository.search(any())).thenReturn(eventList(1));

    AuditEventPage page = service.search(c);

    assertThat(page.cursor()).isEqualTo(cursor.encode());
    assertThat(page.nextCursor()).isNull();
  }

  private static List<AuditEvent> eventList(int size) {
    java.util.List<AuditEvent> events = new java.util.ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      events.add(
          new AuditEvent(
              java.util.UUID.randomUUID(),
              T0.plusSeconds(i),
              "alice",
              "act",
              "res",
              AuditOutcome.SUCCESS,
              com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(),
              null,
              "h" + i,
              (long) i));
    }
    return events;
  }
}
