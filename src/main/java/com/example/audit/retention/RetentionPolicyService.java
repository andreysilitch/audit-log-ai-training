package com.example.audit.retention;

import com.example.audit.domain.AuditEvent;
import com.example.audit.domain.AuditEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Moves events older than {@code audit.retention.days} into the archive table.
 *
 * <p>Physical deletion from the main table is intentionally NOT performed here: AGENTS.md invariant
 * 5 requires that any such deletion be a separate, explicit architectural decision with its own
 * tests and audit of the immutable guarantee in the archive store.
 */
@Service
public class RetentionPolicyService {

  private static final Logger log = LoggerFactory.getLogger(RetentionPolicyService.class);
  private static final int BATCH_SIZE = 500;

  private final AuditEventRepository repository;
  private final ArchivalService archivalService;
  private final Clock clock;
  private final int retentionDays;

  public RetentionPolicyService(
      AuditEventRepository repository,
      ArchivalService archivalService,
      Clock clock,
      @Value("${audit.retention.days:90}") int retentionDays) {
    this.repository = repository;
    this.archivalService = archivalService;
    this.clock = clock;
    this.retentionDays = retentionDays;
  }

  @Scheduled(cron = "${audit.retention.cron:0 0 3 * * *}")
  public void run() {
    Instant cutoff = clock.instant().minus(Duration.ofDays(retentionDays));
    List<AuditEvent> batch = repository.findOlderThan(cutoff, BATCH_SIZE);
    if (batch.isEmpty()) {
      log.debug("Retention run: nothing older than {}", cutoff);
      return;
    }
    int archived = archivalService.archive(batch);
    log.info(
        "Retention run: archived {} of {} events older than {}", archived, batch.size(), cutoff);
  }
}
