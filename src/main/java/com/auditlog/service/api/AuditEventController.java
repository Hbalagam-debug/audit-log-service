package com.auditlog.service.api;

import com.auditlog.service.api.dto.AuditEventCreateRequest;
import com.auditlog.service.api.dto.AuditEventResponse;
import com.auditlog.service.api.dto.QueryResponse;
import com.auditlog.service.api.dto.VerificationResultResponse;
import com.auditlog.service.domain.AuditEvent;
import com.auditlog.service.service.AuditEventService;
import com.auditlog.service.service.ChainVerificationService;
import com.auditlog.service.service.QueryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/audit")
public class AuditEventController {
    private final AuditEventService auditEventService;
    private final QueryService queryService;
    private final ChainVerificationService verificationService;

    public AuditEventController(
        AuditEventService auditEventService,
        QueryService queryService,
        ChainVerificationService verificationService
    ) {
        this.auditEventService = auditEventService;
        this.queryService = queryService;
        this.verificationService = verificationService;
    }

    @PostMapping("/events")
    public ResponseEntity<AuditEventResponse> createEvent(@Valid @RequestBody AuditEventCreateRequest request) {
        AuditEvent event = auditEventService.createEvent(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(AuditEventResponse.fromDomain(event));
    }

    @GetMapping("/events")
    public ResponseEntity<QueryResponse> queryEvents(
        @RequestParam(required = false) String actorId,
        @RequestParam(required = false) String resourceType,
        @RequestParam(required = false) String resourceId,
        @RequestParam(required = false) String eventType,
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to,
        @RequestParam(required = false) String cursor,
        @RequestParam(required = false) Integer limit
    ) {
        QueryResponse response = queryService.query(
            actorId,
            resourceType,
            resourceId,
            eventType,
            from,
            to,
            cursor,
            limit
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/verify")
    public ResponseEntity<VerificationResultResponse> verifyChain() {
        return ResponseEntity.ok(
            VerificationResultResponse.fromDomain(verificationService.verify())
        );
    }
}
