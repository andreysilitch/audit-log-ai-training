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
      String actor, String resource, Instant from, Instant to, int limit, int offset) {
    return new AuditEventSearchCriteria(actor, resource, from, to, limit, offset);
  }

  @Test
  void rejectsNullFrom() {
    assertThatThrownBy(() -> service.search(criteria("alice", null, null, T1, 50, 0)))
        .isInstanceOf(QueryValidationException.class)
        .hasMessageContaining("from and to");
  }

  @Test
  void rejectsNullTo() {
    assertThatThrownBy(() -> service.search(criteria("alice", null, T0, null, 50, 0)))
        .isInstanceOf(QueryValidationException.class)
        .hasMessageContaining("from and to");
  }

  @Test
  void rejectsFromEqualToTo() {
    assertThatThrownBy(() -> service.search(criteria("alice", null, T0, T0, 50, 0)))
        .isInstanceOf(QueryValidationException.class)
        .hasMessageContaining("from must be before to");
  }

  @Test
  void rejectsFromAfterTo() {
    assertThatThrownBy(() -> service.search(criteria("alice", null, T1, T0, 50, 0)))
        .isInstanceOf(QueryValidationException.class)
        .hasMessageContaining("from must be before to");
  }

  @Test
  void rejectsBothActorAndResourceAbsent() {
    assertThatThrownBy(() -> service.search(criteria(null, null, T0, T1, 50, 0)))
        .isInstanceOf(QueryValidationException.class)
        .hasMessageContaining("at least one of actor or resource");
  }

  @Test
  void rejectsBothActorAndResourceBlank() {
    assertThatThrownBy(() -> service.search(criteria("", "  ", T0, T1, 50, 0)))
        .isInstanceOf(QueryValidationException.class)
        .hasMessageContaining("at least one of actor or resource");
  }

  @Test
  void rejectsBlankActorWhenResourceProvided() {
    assertThatThrownBy(() -> service.search(criteria(" ", "order/9", T0, T1, 50, 0)))
        .isInstanceOf(QueryValidationException.class)
        .hasMessageContaining("actor must not be blank");
  }

  @Test
  void rejectsBlankResourceWhenActorProvided() {
    assertThatThrownBy(() -> service.search(criteria("alice", "", T0, T1, 50, 0)))
        .isInstanceOf(QueryValidationException.class)
        .hasMessageContaining("resource must not be blank");
  }

  @Test
  void rejectsLimitZero() {
    assertThatThrownBy(() -> service.search(criteria("alice", null, T0, T1, 0, 0)))
        .isInstanceOf(QueryValidationException.class)
        .hasMessageContaining("limit must be between 1 and 200");
  }

  @Test
  void rejectsLimitAboveMax() {
    assertThatThrownBy(() -> service.search(criteria("alice", null, T0, T1, 201, 0)))
        .isInstanceOf(QueryValidationException.class)
        .hasMessageContaining("limit must be between 1 and 200");
  }

  @Test
  void rejectsNegativeOffset() {
    assertThatThrownBy(() -> service.search(criteria("alice", null, T0, T1, 50, -1)))
        .isInstanceOf(QueryValidationException.class)
        .hasMessageContaining("offset must be non-negative");
  }

  @Test
  void happyPathCallsRepository() {
    AuditEventSearchCriteria c = criteria("alice", null, T0, T1, 50, 0);
    when(repository.search(any())).thenReturn(List.of());
    List<AuditEvent> result = service.search(c);
    assertThat(result).isEmpty();
    verify(repository, times(1)).search(c);
  }
}
