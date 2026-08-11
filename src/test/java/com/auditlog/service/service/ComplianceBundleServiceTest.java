package com.auditlog.service.service;

import com.auditlog.service.api.dto.AuditEventResponse;
import com.auditlog.service.api.dto.ComplianceBundleRequest;
import com.auditlog.service.config.ComplianceReportProperties;
import com.auditlog.service.config.ExportProperties;
import com.auditlog.service.domain.AuditEvent;
import com.auditlog.service.repository.AuditEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("ComplianceBundleService - H2 Unit Tests")
class ComplianceBundleServiceTest {

    @Mock private AuditEventRepository repository;
    @Mock private RedactionViewService redactionViewService;
    @Mock private AuditEventService auditEventService;
    @Mock private CanonicalHashService canonicalHashService;
    @Mock private ExportProperties exportProperties;
    @Mock private ExportProperties.SigningProperties signingProperties;

    private ComplianceReportService service;
    private ComplianceReportProperties properties;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-08-11T12:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        properties = new ComplianceReportProperties();
        when(exportProperties.getSigning()).thenReturn(signingProperties);
        when(signingProperties.isEnabled()).thenReturn(false);
        when(exportProperties.getMaxRecords()).thenReturn(10000);

        service = new ComplianceReportService(
            repository, redactionViewService, properties,
            auditEventService, canonicalHashService, exportProperties, fixedClock
        );
    }

    @Test
    @DisplayName("generateSignedBundle rejects missing approvalRef")
    void testRejectsMissingApprovalRef() {
        String now = Instant.now().toString();
        String later = Instant.now().plus(1, ChronoUnit.DAYS).toString();
        assertThrows(IllegalArgumentException.class,
            () -> service.generateSignedBundle(request("acc-1", null, now, later, null, null)));
    }

    @Test
    @DisplayName("generateSignedBundle rejects blank approvalRef")
    void testRejectsBlankApprovalRef() {
        String now = Instant.now().toString();
        String later = Instant.now().plus(1, ChronoUnit.DAYS).toString();
        assertThrows(IllegalArgumentException.class,
            () -> service.generateSignedBundle(request("acc-1", null, now, later, "   ", null)));
    }

    @Test
    @DisplayName("generateSignedBundle rejects both accountId and resourceId")
    void testRejectsBothSelectors() {
        String now = Instant.now().toString();
        String later = Instant.now().plus(1, ChronoUnit.DAYS).toString();
        assertThrows(IllegalArgumentException.class,
            () -> service.generateSignedBundle(request("acc-1", "res-1", now, later, "APPROVAL-001", null)));
    }

    @Test
    @DisplayName("generateSignedBundle rejects neither accountId nor resourceId")
    void testRejectsNeitherSelector() {
        String now = Instant.now().toString();
        String later = Instant.now().plus(1, ChronoUnit.DAYS).toString();
        assertThrows(IllegalArgumentException.class,
            () -> service.generateSignedBundle(request(null, null, now, later, "APPROVAL-001", null)));
    }

    @Test
    @DisplayName("generateSignedBundle rejects missing from timestamp")
    void testRejectsMissingFrom() {
        String later = Instant.now().plus(1, ChronoUnit.DAYS).toString();
        assertThrows(IllegalArgumentException.class,
            () -> service.generateSignedBundle(request("acc-1", null, null, later, "APPROVAL-001", null)));
    }

    @Test
    @DisplayName("generateSignedBundle rejects invalid timestamp range (from >= to)")
    void testRejectsInvalidTimestampRange() {
        String now = Instant.now().toString();
        assertThrows(IllegalArgumentException.class,
            () -> service.generateSignedBundle(request("acc-1", null, now, now, "APPROVAL-001", null)));
    }

    @Test
    @DisplayName("generateSignedBundle rejects excessive UTC window")
    void testRejectsExcessiveTimeWindow() {
        String from = Instant.now().toString();
        String to   = Instant.now().plus(200, ChronoUnit.DAYS).toString();
        assertThrows(IllegalArgumentException.class,
            () -> service.generateSignedBundle(request("acc-1", null, from, to, "APPROVAL-001", null)));
    }

    @Test
    @DisplayName("generateSignedBundle excludes COMPLIANCE_REPORT_GENERATED events from bundle records")
    void testExcludesCertificateEventsFromBundle() {
        String from = "2026-08-11T00:00:00Z";
        String to   = "2026-08-12T00:00:00Z";

        AuditEvent accessEvent = makeEvent(1L, "CLIENT_ACCOUNT_DATA_VIEWED");
        AuditEvent certEvent   = makeEvent(2L, "COMPLIANCE_REPORT_GENERATED");

        when(repository.findWithFilters(any(), any(), any(), isNull(), any(), any(), isNull(), anyLong(), anyBoolean()))
            .thenReturn(List.of(accessEvent, certEvent));
        when(redactionViewService.maskEvents(List.of(accessEvent))).thenReturn(List.of());
        when(canonicalHashService.canonicalizeValue(any())).thenReturn("[]");
        when(canonicalHashService.sha256Hex(any())).thenReturn("a".repeat(64));

        service.generateSignedBundle(request("acc-1", null, from, to, "APPROVAL-001", null));

        verify(redactionViewService).maskEvents(List.of(accessEvent));
        verify(auditEventService).createEvent(any());
    }

    @Test
    @DisplayName("generateSignedBundle only includes the five access-event types")
    void testTaxonomyFiltering() {
        String from = "2026-08-11T00:00:00Z";
        String to   = "2026-08-12T00:00:00Z";

        AuditEvent validEvent   = makeEvent(1L, "CLIENT_ACCOUNT_DATA_VIEWED");
        AuditEvent invalidEvent = makeEvent(2L, "USER_LOGIN");

        when(repository.findWithFilters(any(), any(), any(), isNull(), any(), any(), isNull(), anyLong(), anyBoolean()))
            .thenReturn(List.of(validEvent, invalidEvent));
        when(redactionViewService.maskEvents(List.of(validEvent))).thenReturn(List.of());
        when(canonicalHashService.canonicalizeValue(any())).thenReturn("[]");
        when(canonicalHashService.sha256Hex(any())).thenReturn("b".repeat(64));

        service.generateSignedBundle(request("acc-1", null, from, to, "APPROVAL-001", null));

        verify(redactionViewService).maskEvents(List.of(validEvent));
    }

    @Test
    @DisplayName("generateSignedBundle appends certificate event exactly once on success")
    void testAppendsCertificateEventOnSuccess() {
        String from = "2026-08-11T00:00:00Z";
        String to   = "2026-08-12T00:00:00Z";

        when(repository.findWithFilters(any(), any(), any(), isNull(), any(), any(), isNull(), anyLong(), anyBoolean()))
            .thenReturn(List.of());
        when(redactionViewService.maskEvents(any())).thenReturn(List.of());
        when(canonicalHashService.canonicalizeValue(any())).thenReturn("[]");
        when(canonicalHashService.sha256Hex(any())).thenReturn("c".repeat(64));

        service.generateSignedBundle(request("acc-1", null, from, to, "APPROVAL-XYZ", "AUDIT"));

        verify(auditEventService, times(1)).createEvent(argThat(req ->
            "COMPLIANCE_REPORT_GENERATED".equals(req.getEventType())
            && "APPROVAL-XYZ".equals(req.getResourceId())
            && "COMPLIANCE_REPORT".equals(req.getResourceType())
        ));
    }

    @Test
    @DisplayName("generateSignedBundle does not append certificate event when bundle creation fails")
    void testNoCertEventWhenBundleFails() {
        String from = "2026-08-11T00:00:00Z";
        String to   = "2026-08-12T00:00:00Z";

        when(repository.findWithFilters(any(), any(), any(), isNull(), any(), any(), isNull(), anyLong(), anyBoolean()))
            .thenReturn(List.of());
        when(redactionViewService.maskEvents(any())).thenReturn(List.of());
        when(canonicalHashService.canonicalizeValue(any())).thenThrow(new RuntimeException("digest failure"));

        assertThrows(RuntimeException.class,
            () -> service.generateSignedBundle(request("acc-1", null, from, to, "APPROVAL-001", null)));
        verify(auditEventService, never()).createEvent(any());
    }

    @Test
    @DisplayName("3-arg constructor throws IllegalStateException when generateSignedBundle is called")
    void testBundleCapabilityCheckOnMinimalConstructor() {
        ComplianceReportService minimalService = new ComplianceReportService(
            repository, redactionViewService, properties
        );
        String now = Instant.now().toString();
        String later = Instant.now().plus(1, ChronoUnit.DAYS).toString();
        assertThrows(IllegalStateException.class,
            () -> minimalService.generateSignedBundle(request("acc-1", null, now, later, "APPROVAL-001", null)));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ComplianceBundleRequest request(
        String accountId, String resourceId,
        String from, String to,
        String approvalRef, String reasonCode
    ) {
        ComplianceBundleRequest r = new ComplianceBundleRequest();
        r.setAccountId(accountId);
        r.setResourceId(resourceId);
        r.setFrom(from);
        r.setTo(to);
        r.setApprovalRef(approvalRef);
        r.setReasonCode(reasonCode);
        return r;
    }

    private AuditEvent makeEvent(long chainPosition, String eventType) {
        return new AuditEvent(
            "id-" + chainPosition, chainPosition, eventType, "actor-1",
            "CLIENT_ACCOUNT", "acc-1",
            objectMapper.createObjectNode(),
            "2026-08-11T10:00:00Z", "2026-08-11T10:00:00Z",
            "hash-" + chainPosition, "prev-" + chainPosition,
            "chain-" + chainPosition, "v1"
        );
    }
}

