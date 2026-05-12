package com.example.audit.api;

import com.example.audit.domain.AuditEvent;
import com.example.audit.domain.AuditEventSearchCriteria;
import com.example.audit.domain.AuditEventService;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/audit-events")
public class AuditEventController {

  private static final int DEFAULT_LIMIT = 50;
  private static final int DEFAULT_OFFSET = 0;

  private final AuditEventService service;

  public AuditEventController(AuditEventService service) {
    this.service = service;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public AuditEventResponse append(@Valid @RequestBody AuditEventRequest request) {
    AuditEvent event =
        service.record(
            request.actor(),
            request.action(),
            request.resource(),
            request.outcome(),
            request.context());
    return AuditEventResponse.of(event);
  }

  @GetMapping
  public AuditEventSearchResponse search(
      @RequestParam(required = false) String actor,
      @RequestParam(required = false) String resource,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) Integer offset) {
    int effectiveLimit = limit == null ? DEFAULT_LIMIT : limit;
    int effectiveOffset = offset == null ? DEFAULT_OFFSET : offset;
    var criteria =
        new AuditEventSearchCriteria(actor, resource, from, to, effectiveLimit, effectiveOffset);
    return AuditEventSearchResponse.of(service.search(criteria));
  }
}
