package com.auditlog.service.api;

import com.auditlog.service.api.dto.AuditEventCreateRequest;
import com.auditlog.service.api.dto.AuditEventResponse;
import com.auditlog.service.api.dto.ComplianceBundleRequest;
import com.auditlog.service.api.dto.ComplianceReportRequest;
import com.auditlog.service.api.dto.ComplianceReportResponse;
import com.auditlog.service.api.dto.QueryResponse;
import com.auditlog.service.api.dto.RedactionRequest;
import com.auditlog.service.api.dto.RedactionResponse;
import com.auditlog.service.api.dto.RetentionRunRequest;
import com.auditlog.service.api.dto.RetentionRunResponse;
import com.auditlog.service.api.dto.VerificationResultResponse;
import com.auditlog.service.domain.AuditEvent;
import com.auditlog.service.service.AuditEventService;
import com.auditlog.service.service.ChainVerificationService;
import com.auditlog.service.service.ComplianceReportService;
import com.auditlog.service.service.QueryService;
import com.auditlog.service.service.RedactionService;
import com.auditlog.service.service.ExportService;
import com.auditlog.service.service.RedactionViewService;
import com.auditlog.service.service.RetentionService;
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
    private final RetentionService retentionService;
    private final RedactionService redactionService;
    private final RedactionViewService redactionViewService;
    private final ExportService exportService;
    private final ComplianceReportService complianceReportService;

    public AuditEventController(
        AuditEventService auditEventService,
        QueryService queryService,
        ChainVerificationService verificationService,
        RetentionService retentionService,
        RedactionService redactionService,
        RedactionViewService redactionViewService,
        ExportService exportService,
        ComplianceReportService complianceReportService
    ) {
        this.auditEventService = auditEventService;
        this.queryService = queryService;
        this.verificationService = verificationService;
        this.retentionService = retentionService;
        this.redactionService = redactionService;
        this.redactionViewService = redactionViewService;
        this.exportService = exportService;
        this.complianceReportService = complianceReportService;
    }

    @PostMapping("/events")
    public ResponseEntity<AuditEventResponse> createEvent(@Valid @RequestBody AuditEventCreateRequest request) {
        AuditEvent event = auditEventService.createEvent(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(redactionViewService.maskEvent(event));
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
        @RequestParam(required = false) Integer limit,
        @RequestParam(required = false, defaultValue = "false") boolean includeArchived
    ) {
        QueryResponse response = queryService.query(
            actorId,
            resourceType,
            resourceId,
            eventType,
            from,
            to,
            cursor,
            limit,
            includeArchived
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/retention/run")
    public ResponseEntity<RetentionRunResponse> runRetention(@Valid @RequestBody RetentionRunRequest request) {
        RetentionRunResponse response = retentionService.runRetention(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/events/{id}/redactions")
    public ResponseEntity<RedactionResponse> redactEvent(
        @PathVariable String id,
        @Valid @RequestBody RedactionRequest request
    ) {
        RedactionResponse response = redactionService.applyRedaction(id, request);
        boolean created = response.redactionCertificateEventId() != null && !response.redactionCertificateEventId().isBlank();
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).body(response);
    }

    @GetMapping("/verify")
    public ResponseEntity<VerificationResultResponse> verifyChain() {
        return ResponseEntity.ok(
            VerificationResultResponse.fromDomain(verificationService.verify())
        );
    }

    @GetMapping("/exports")
    public ResponseEntity<tools.jackson.databind.JsonNode> exportEvents(
        @RequestParam(required = false) String actorId,
        @RequestParam(required = false) String resourceId,
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to,
        @RequestParam(required = false, defaultValue = "false") boolean includeArchived
    ) {
        tools.jackson.databind.JsonNode bundle = exportService.export(actorId, resourceId, from, to, includeArchived);
        return ResponseEntity.ok(bundle);
    }

    @GetMapping("/compliance/access-report")
    public ResponseEntity<ComplianceReportResponse> getComplianceAccessReport(
        @RequestParam(required = false) String accountId,
        @RequestParam(required = false) String resourceId,
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to,
        @RequestParam(required = false) String actorId,
        @RequestParam(required = false) String action,
        @RequestParam(required = false) String outcome,
        @RequestParam(required = false, defaultValue = "false") boolean includeArchived,
        @RequestParam(required = false) String cursor,
        @RequestParam(required = false) Integer limit
    ) {
        ComplianceReportRequest request = new ComplianceReportRequest(
            accountId,
            resourceId,
            from,
            to,
            actorId,
            action,
            outcome,
            includeArchived,
            cursor,
            limit
        );
        ComplianceReportResponse response = complianceReportService.queryAccessReport(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/compliance/access-report/bundle")
    public ResponseEntity<tools.jackson.databind.JsonNode> generateComplianceBundle(
        @RequestBody ComplianceBundleRequest request
    ) {
        tools.jackson.databind.JsonNode bundle = complianceReportService.generateSignedBundle(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(bundle);
    }
}
