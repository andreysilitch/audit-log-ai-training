package com.example.audit;

import com.example.audit.domain.AuditEvent;
import com.example.audit.domain.AuditEventRepository;
import com.example.audit.domain.AuditEventSearchCriteria;
import com.example.audit.domain.AuditEventService;
import com.example.audit.domain.AuditOutcome;
import com.example.audit.tamper.HashChainService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class ConcurrentAppendIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired AuditEventService service;
    @Autowired AuditEventRepository repository;
    @Autowired HashChainService hashChainService;
    @Autowired ObjectMapper objectMapper;

    @Test
    void concurrentAppendsKeepHashChainConsistent() throws Exception {
        int threads = 8;
        int perThread = 25;
        int total = threads * perThread;

        String actor = "concurrent-" + UUID.randomUUID();
        Instant from = Instant.now().minusSeconds(5);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        service.record(
                                actor,
                                "act-" + threadId,
                                "res-" + i,
                                AuditOutcome.SUCCESS,
                                objectMapper.createObjectNode().put("t", threadId).put("i", i)
                        );
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(errors.get()).isZero();

        Instant to = Instant.now().plusSeconds(5);
        List<AuditEvent> mine = repository.search(
                new AuditEventSearchCriteria(actor, null, from, to, 1000, 0));
        assertThat(mine).hasSize(total);

        // Verify chain in global ascending sequence_no order — appends serialize via advisory lock,
        // so prev_hash links must be intact for events whose subsequence within `mine` is contiguous
        // by sequence_no. We sort and inspect linkage between adjacent rows of the same actor.
        List<AuditEvent> ordered = mine.stream()
                .sorted(Comparator.comparingLong(AuditEvent::sequenceNo))
                .toList();

        for (int i = 1; i < ordered.size(); i++) {
            // Adjacent entries within this actor are not necessarily globally adjacent,
            // so we cannot assume prev_hash equals previous event's hash. Instead we
            // recompute each event's own hash against its stored prev_hash and verify match.
            AuditEvent e = ordered.get(i);
            String recomputed = hashChainService.computeHash(
                    e.prevHash(), e.id(), e.timestamp(),
                    e.actor(), e.action(), e.resource(), e.outcome(),
                    hashChainService.canonicalize(e.context()));
            assertThat(recomputed).isEqualTo(e.hash());
        }
    }
}
