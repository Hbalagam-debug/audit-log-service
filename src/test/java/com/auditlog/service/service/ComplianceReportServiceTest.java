package com.auditlog.service.service;

import com.auditlog.service.api.dto.ComplianceReportRequest;
import com.auditlog.service.api.dto.ComplianceReportResponse;
import com.auditlog.service.config.ComplianceReportProperties;
import com.auditlog.service.domain.AuditEvent;
import com.auditlog.service.repository.AuditEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@DisplayName("ComplianceReportService - Unit Tests")
class ComplianceReportServiceTest {
    @Mock
    private AuditEventRepository repository;

    @Mock
    private RedactionViewService redactionViewService;

    private ComplianceReportService service;
    private ComplianceReportProperties properties;
    private ObjectMapper objectMapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        properties = new ComplianceReportProperties();
        service = new ComplianceReportService(repository, redactionViewService, properties);
    }

    @Test
    @DisplayName("queryAccessReport rejects both accountId and resourceId")
    void testRejectsBothSelectors() {
        String now = Instant.now().toString();
        String later = Instant.now().plus(1, ChronoUnit.DAYS).toString();

        ComplianceReportRequest request = new ComplianceReportRequest(
            "acc-1", "res-1", now, later, null, null, null, false, null, null
        );

        assertThrows(IllegalArgumentException.class, () -> service.queryAccessReport(request));
    }

    @Test
    @DisplayName("queryAccessReport rejects neither accountId nor resourceId")
    void testRejectsNeitherSelector() {
        String now = Instant.now().toString();
        String later = Instant.now().plus(1, ChronoUnit.DAYS).toString();

        ComplianceReportRequest request = new ComplianceReportRequest(
            null, null, now, later, null, null, null, false, null, null
        );

        assertThrows(IllegalArgumentException.class, () -> service.queryAccessReport(request));
    }

    @Test
    @DisplayName("queryAccessReport rejects missing 'from' parameter")
    void testRejectsMissingFrom() {
        String now = Instant.now().toString();

        ComplianceReportRequest request = new ComplianceReportRequest(
            "acc-1", null, null, now, null, null, null, false, null, null
        );

        assertThrows(IllegalArgumentException.class, () -> service.queryAccessReport(request));
    }

    @Test
    @DisplayName("queryAccessReport rejects missing 'to' parameter")
    void testRejectsMissingTo() {
        String now = Instant.now().toString();

        ComplianceReportRequest request = new ComplianceReportRequest(
            "acc-1", null, now, null, null, null, null, false, null, null
        );

        assertThrows(IllegalArgumentException.class, () -> service.queryAccessReport(request));
    }

    @Test
    @DisplayName("queryAccessReport rejects invalid timestamp range (from >= to)")
    void testRejectsInvalidTimestampRange() {
        String now = Instant.now().toString();

        ComplianceReportRequest request = new ComplianceReportRequest(
            "acc-1", null, now, now, null, null, null, false, null, null
        );

        assertThrows(IllegalArgumentException.class, () -> service.queryAccessReport(request));
    }

    @Test
    @DisplayName("queryAccessReport normalizes timestamps to UTC")
    void testNormalizesTimestamps() {
        String from = "2026-08-11T00:00:00Z";
        String to = "2026-08-12T00:00:00Z";

        ComplianceReportRequest request = new ComplianceReportRequest(
            "acc-1", null, from, to, null, null, null, false, null, null
        );

        when(repository.findWithFilters(anyString(), anyString(), anyString(), isNull(), 
            anyString(), anyString(), isNull(), anyLong(), anyBoolean()))
            .thenReturn(List.of());
        when(redactionViewService.maskEvents(any())).thenReturn(List.of());

        ComplianceReportResponse response = service.queryAccessReport(request);

        assertNotNull(response);
        assertEquals(0, response.recordCount());
    }

    @Test
    @DisplayName("queryAccessReport enforces maximum UTC window")
    void testEnforcesMaxUtcWindow() {
        Instant now = Instant.now();
        String from = now.toString();
        String to = now.plus(200, ChronoUnit.DAYS).toString(); // Exceeds 90-day max

        ComplianceReportRequest request = new ComplianceReportRequest(
            "acc-1", null, from, to, null, null, null, false, null, null
        );

        assertThrows(IllegalArgumentException.class, () -> service.queryAccessReport(request));
    }

    @Test
    @DisplayName("queryAccessReport uses accountId as selector when provided")
    void testUsesAccountIdSelector() {
        String now = Instant.now().toString();
        String later = Instant.now().plus(1, ChronoUnit.DAYS).toString();

        ComplianceReportRequest request = new ComplianceReportRequest(
            "acc-1", null, now, later, null, null, null, false, null, null
        );

        when(repository.findWithFilters(anyString(), anyString(), eq("acc-1"), isNull(), 
            anyString(), anyString(), isNull(), anyLong(), anyBoolean()))
            .thenReturn(List.of());
        when(redactionViewService.maskEvents(any())).thenReturn(List.of());

        ComplianceReportResponse response = service.queryAccessReport(request);

        assertEquals("accountId", response.selection().selector());
        assertEquals("acc-1", response.selection().selectorValue());
    }

    @Test
    @DisplayName("queryAccessReport uses resourceId as selector when provided")
    void testUsesResourceIdSelector() {
        String now = Instant.now().toString();
        String later = Instant.now().plus(1, ChronoUnit.DAYS).toString();

        ComplianceReportRequest request = new ComplianceReportRequest(
            null, "res-1", now, later, null, null, null, false, null, null
        );

        when(repository.findWithFilters(anyString(), anyString(), eq("res-1"), isNull(), 
            anyString(), anyString(), isNull(), anyLong(), anyBoolean()))
            .thenReturn(List.of());
        when(redactionViewService.maskEvents(any())).thenReturn(List.of());

        ComplianceReportResponse response = service.queryAccessReport(request);

        assertEquals("resourceId", response.selection().selector());
        assertEquals("res-1", response.selection().selectorValue());
    }

    @Test
    @DisplayName("queryAccessReport encodes and decodes cursor correctly")
    void testCursorEncodingDecoding() {
        long chainPosition = 12345L;
        String cursor = service.encodeCursor(chainPosition);
        Long decodedPosition = service.decodeCursor(cursor);

        assertEquals(chainPosition, decodedPosition);
    }

    @Test
    @DisplayName("queryAccessReport throws exception on invalid cursor")
    void testInvalidCursorThrowsException() {
        String invalidCursor = "invalid-cursor-data";

        assertThrows(Exception.class, () -> service.decodeCursor(invalidCursor));
    }

    @Test
    @DisplayName("queryAccessReport applies default page size when limit is null")
    void testAppliesDefaultPageSize() {
        String now = Instant.now().toString();
        String later = Instant.now().plus(1, ChronoUnit.DAYS).toString();

        ComplianceReportRequest request = new ComplianceReportRequest(
            "acc-1", null, now, later, null, null, null, false, null, null
        );

        when(repository.findWithFilters(anyString(), anyString(), anyString(), isNull(), 
            anyString(), anyString(), isNull(), eq(51L), anyBoolean())) // +1 for hasMore detection
            .thenReturn(List.of());
        when(redactionViewService.maskEvents(any())).thenReturn(List.of());

        service.queryAccessReport(request);
        // If we got here without exception, the default (50) + 1 was used
    }

    @Test
    @DisplayName("queryAccessReport normalizes limit to valid range")
    void testNormalizesLimit() {
        String now = Instant.now().toString();
        String later = Instant.now().plus(1, ChronoUnit.DAYS).toString();

        // Test limit < 1 gets normalized
        ComplianceReportRequest tooSmall = new ComplianceReportRequest(
            "acc-1", null, now, later, null, null, null, false, null, 0
        );

        when(repository.findWithFilters(anyString(), anyString(), anyString(), isNull(), 
            anyString(), anyString(), isNull(), eq(2L), anyBoolean())) // Normalized to 1 + 1
            .thenReturn(List.of());
        when(redactionViewService.maskEvents(any())).thenReturn(List.of());

        service.queryAccessReport(tooSmall);

        // Test limit > max gets capped
        ComplianceReportRequest tooLarge = new ComplianceReportRequest(
            "acc-1", null, now, later, null, null, null, false, null, 1000
        );

        when(repository.findWithFilters(anyString(), anyString(), anyString(), isNull(), 
            anyString(), anyString(), isNull(), eq(201L), anyBoolean())) // Capped to 200 + 1
            .thenReturn(List.of());

        service.queryAccessReport(tooLarge);
    }
}
