package com.auditlog.service.integration;

import com.auditlog.service.SpringBootTestSupport;
import com.auditlog.service.api.dto.AuditEventCreateRequest;
import com.auditlog.service.api.dto.ComplianceReportResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Compliance Report - Scenario C H1 Integration Tests")
class ComplianceReportControllerIntegrationTest extends SpringBootTestSupport {
    @Autowired
    private WebApplicationContext wac;

    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ObjectMapper objectMapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        jdbcTemplate.update("DELETE FROM audit_events");
    }

    @Test
    @DisplayName("GET /compliance/access-report requires exactly one selector (accountId or resourceId)")
    void testRequiresExactlyOneSelector() throws Exception {
        String now = Instant.now().toString();
        String later = Instant.now().plus(1, ChronoUnit.DAYS).toString();

        // Both selectors provided - should fail
        mockMvc.perform(
            get("/audit/compliance/access-report")
                .param("accountId", "acc-1")
                .param("resourceId", "res-1")
                .param("from", now)
                .param("to", later)
        )
        .andExpect(status().isBadRequest());

        // No selector provided - should fail
        mockMvc.perform(
            get("/audit/compliance/access-report")
                .param("from", now)
                .param("to", later)
        )
        .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /compliance/access-report requires 'from' and 'to' parameters")
    void testRequiresTimestampRange() throws Exception {
        // Missing 'to'
        mockMvc.perform(
            get("/audit/compliance/access-report")
                .param("accountId", "acc-1")
                .param("from", Instant.now().toString())
        )
        .andExpect(status().isBadRequest());

        // Missing 'from'
        mockMvc.perform(
            get("/audit/compliance/access-report")
                .param("accountId", "acc-1")
                .param("to", Instant.now().toString())
        )
        .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /compliance/access-report rejects invalid timestamp range (from >= to)")
    void testRejectsInvalidTimestampRange() throws Exception {
        String now = Instant.now().toString();

        mockMvc.perform(
            get("/audit/compliance/access-report")
                .param("accountId", "acc-1")
                .param("from", now)
                .param("to", now)
        )
        .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /compliance/access-report returns empty results when no matching events exist")
    void testEmptyReportWithNoMatchingEvents() throws Exception {
        String now = Instant.now().toString();
        String later = Instant.now().plus(1, ChronoUnit.DAYS).toString();

        MvcResult result = mockMvc.perform(
            get("/audit/compliance/access-report")
                .param("accountId", "acc-1")
                .param("from", now)
                .param("to", later)
        )
        .andExpect(status().isOk())
        .andReturn();

        ComplianceReportResponse response = objectMapper.readValue(
            result.getResponse().getContentAsString(),
            ComplianceReportResponse.class
        );

        assertEquals(0, response.recordCount());
        assertTrue(response.items().isEmpty());
        assertFalse(response.hasMore());
        assertNull(response.nextCursor());
        assertEquals("accountId", response.selection().selector());
        assertEquals("acc-1", response.selection().selectorValue());
    }

    @Test
    @DisplayName("GET /compliance/access-report includes only allowed access-event types")
    void testIncludesOnlyAccessEventTypes() throws Exception {
        String now = Instant.now().toString();
        String later = Instant.now().plus(1, ChronoUnit.DAYS).toString();

        // Create various event types
        createTestEvent("CLIENT_ACCOUNT_DATA_VIEWED", "user-1", "CLIENT_ACCOUNT", "acc-1");
        createTestEvent("CLIENT_ACCOUNT_DATA_SEARCHED", "user-1", "CLIENT_ACCOUNT", "acc-1");
        createTestEvent("USER_LOGIN", "user-1", "CLIENT_ACCOUNT", "acc-1"); // Not in access taxonomy
        createTestEvent("SYSTEM_ERROR", "admin", "CLIENT_ACCOUNT", "acc-1"); // Not in access taxonomy

        MvcResult result = mockMvc.perform(
            get("/audit/compliance/access-report")
                .param("accountId", "acc-1")
                .param("from", now)
                .param("to", later)
        )
        .andExpect(status().isOk())
        .andReturn();

        ComplianceReportResponse response = objectMapper.readValue(
            result.getResponse().getContentAsString(),
            ComplianceReportResponse.class
        );

        // Should only have 2 access events (VIEWED, SEARCHED), not the other types
        assertEquals(2, response.recordCount());
        assertEquals(2, response.items().size());
    }

    @Test
    @DisplayName("GET /compliance/access-report excludes COMPLIANCE_REPORT_GENERATED events")
    void testExcludesComplianceReportGeneratedEvents() throws Exception {
        String now = Instant.now().toString();
        String later = Instant.now().plus(1, ChronoUnit.DAYS).toString();

        createTestEvent("CLIENT_ACCOUNT_DATA_VIEWED", "user-1", "CLIENT_ACCOUNT", "acc-1");
        createTestEvent("COMPLIANCE_REPORT_GENERATED", "admin", "CLIENT_ACCOUNT", "acc-1");
        createTestEvent("CLIENT_ACCOUNT_DATA_EXPORTED", "user-1", "CLIENT_ACCOUNT", "acc-1");

        MvcResult result = mockMvc.perform(
            get("/audit/compliance/access-report")
                .param("accountId", "acc-1")
                .param("from", now)
                .param("to", later)
        )
        .andExpect(status().isOk())
        .andReturn();

        ComplianceReportResponse response = objectMapper.readValue(
            result.getResponse().getContentAsString(),
            ComplianceReportResponse.class
        );

        // Should have 2 access events, not the COMPLIANCE_REPORT_GENERATED
        assertEquals(2, response.recordCount());
        assertTrue(response.items().stream()
            .noneMatch(e -> "COMPLIANCE_REPORT_GENERATED".equals(e.eventType())));
    }

    @Test
    @DisplayName("GET /compliance/access-report excludes archived events by default")
    void testExcludesArchivedEventsByDefault() throws Exception {
        String now = Instant.now().toString();
        String later = Instant.now().plus(1, ChronoUnit.DAYS).toString();

        createTestEvent("CLIENT_ACCOUNT_DATA_VIEWED", "user-1", "CLIENT_ACCOUNT", "acc-1");
        String eventId = createTestEvent("CLIENT_ACCOUNT_DATA_SEARCHED", "user-1", "CLIENT_ACCOUNT", "acc-1");

        // Archive the second event
        jdbcTemplate.update("UPDATE audit_events SET is_archived = TRUE WHERE id = ?", eventId);

        MvcResult result = mockMvc.perform(
            get("/audit/compliance/access-report")
                .param("accountId", "acc-1")
                .param("from", now)
                .param("to", later)
        )
        .andExpect(status().isOk())
        .andReturn();

        ComplianceReportResponse response = objectMapper.readValue(
            result.getResponse().getContentAsString(),
            ComplianceReportResponse.class
        );

        // Should only have 1 event (archived one excluded)
        assertEquals(1, response.recordCount());
        assertEquals("CLIENT_ACCOUNT_DATA_VIEWED", response.items().get(0).eventType());
    }

    @Test
    @DisplayName("GET /compliance/access-report includes archived events when includeArchived=true")
    void testIncludesArchivedWhenRequested() throws Exception {
        String now = Instant.now().toString();
        String later = Instant.now().plus(1, ChronoUnit.DAYS).toString();

        createTestEvent("CLIENT_ACCOUNT_DATA_VIEWED", "user-1", "CLIENT_ACCOUNT", "acc-1");
        String eventId = createTestEvent("CLIENT_ACCOUNT_DATA_SEARCHED", "user-1", "CLIENT_ACCOUNT", "acc-1");

        // Archive the second event
        jdbcTemplate.update("UPDATE audit_events SET is_archived = TRUE WHERE id = ?", eventId);

        MvcResult result = mockMvc.perform(
            get("/audit/compliance/access-report")
                .param("accountId", "acc-1")
                .param("from", now)
                .param("to", later)
                .param("includeArchived", "true")
        )
        .andExpect(status().isOk())
        .andReturn();

        ComplianceReportResponse response = objectMapper.readValue(
            result.getResponse().getContentAsString(),
            ComplianceReportResponse.class
        );

        // Should have both events when includeArchived=true
        assertEquals(2, response.recordCount());
    }

    @Test
    @DisplayName("GET /compliance/access-report filters by actorId")
    void testFiltersActorId() throws Exception {
        String now = Instant.now().toString();
        String later = Instant.now().plus(1, ChronoUnit.DAYS).toString();

        createTestEvent("CLIENT_ACCOUNT_DATA_VIEWED", "user-1", "CLIENT_ACCOUNT", "acc-1");
        createTestEvent("CLIENT_ACCOUNT_DATA_SEARCHED", "user-2", "CLIENT_ACCOUNT", "acc-1");
        createTestEvent("CLIENT_ACCOUNT_DATA_EXPORTED", "user-1", "CLIENT_ACCOUNT", "acc-1");

        MvcResult result = mockMvc.perform(
            get("/audit/compliance/access-report")
                .param("accountId", "acc-1")
                .param("actorId", "user-1")
                .param("from", now)
                .param("to", later)
        )
        .andExpect(status().isOk())
        .andReturn();

        ComplianceReportResponse response = objectMapper.readValue(
            result.getResponse().getContentAsString(),
            ComplianceReportResponse.class
        );

        // Should only have user-1's events
        assertEquals(2, response.recordCount());
        assertTrue(response.items().stream().allMatch(e -> "user-1".equals(e.actorId())));
    }

    @Test
    @DisplayName("GET /compliance/access-report supports cursor pagination")
    void testCursorPagination() throws Exception {
        String now = Instant.now().toString();
        String later = Instant.now().plus(1, ChronoUnit.DAYS).toString();

        for (int i = 0; i < 5; i++) {
            createTestEvent("CLIENT_ACCOUNT_DATA_VIEWED", "user-1", "CLIENT_ACCOUNT", "acc-1");
        }

        // First page with limit=2
        MvcResult firstPage = mockMvc.perform(
            get("/audit/compliance/access-report")
                .param("accountId", "acc-1")
                .param("from", now)
                .param("to", later)
                .param("limit", "2")
        )
        .andExpect(status().isOk())
        .andReturn();

        ComplianceReportResponse firstResponse = objectMapper.readValue(
            firstPage.getResponse().getContentAsString(),
            ComplianceReportResponse.class
        );

        assertEquals(2, firstResponse.items().size());
        assertTrue(firstResponse.hasMore());
        assertNotNull(firstResponse.nextCursor());

        // Second page using cursor
        MvcResult secondPage = mockMvc.perform(
            get("/audit/compliance/access-report")
                .param("accountId", "acc-1")
                .param("from", now)
                .param("to", later)
                .param("cursor", firstResponse.nextCursor())
                .param("limit", "2")
        )
        .andExpect(status().isOk())
        .andReturn();

        ComplianceReportResponse secondResponse = objectMapper.readValue(
            secondPage.getResponse().getContentAsString(),
            ComplianceReportResponse.class
        );

        assertEquals(2, secondResponse.items().size());
        assertTrue(secondResponse.hasMore());

        // Verify events are different
        assertNotEquals(
            firstResponse.items().get(0).id(),
            secondResponse.items().get(0).id()
        );
    }

    @Test
    @DisplayName("GET /compliance/access-report respects limit parameter (max page size)")
    void testRespectMaxPageSize() throws Exception {
        String now = Instant.now().toString();
        String later = Instant.now().plus(1, ChronoUnit.DAYS).toString();

        for (int i = 0; i < 5; i++) {
            createTestEvent("CLIENT_ACCOUNT_DATA_VIEWED", "user-1", "CLIENT_ACCOUNT", "acc-1");
        }

        MvcResult result = mockMvc.perform(
            get("/audit/compliance/access-report")
                .param("accountId", "acc-1")
                .param("from", now)
                .param("to", later)
                .param("limit", "1000") // Exceeds max
        )
        .andExpect(status().isOk())
        .andReturn();

        ComplianceReportResponse response = objectMapper.readValue(
            result.getResponse().getContentAsString(),
            ComplianceReportResponse.class
        );

        // Should be capped at default max page size
        assertTrue(response.items().size() <= 200);
    }

    @Test
    @DisplayName("GET /compliance/access-report returns items in deterministic chain-position order")
    void testDeterministicOrdering() throws Exception {
        String now = Instant.now().toString();
        String later = Instant.now().plus(1, ChronoUnit.DAYS).toString();

        for (int i = 0; i < 3; i++) {
            createTestEvent("CLIENT_ACCOUNT_DATA_VIEWED", "user-1", "CLIENT_ACCOUNT", "acc-1");
        }

        MvcResult result = mockMvc.perform(
            get("/audit/compliance/access-report")
                .param("accountId", "acc-1")
                .param("from", now)
                .param("to", later)
        )
        .andExpect(status().isOk())
        .andReturn();

        ComplianceReportResponse response = objectMapper.readValue(
            result.getResponse().getContentAsString(),
            ComplianceReportResponse.class
        );

        // Verify chain positions are in ascending order
        long prevPosition = -1;
        for (var item : response.items()) {
            long chainPosition = item.chainPosition();
            assertTrue(chainPosition > prevPosition, "Chain positions must be in ascending order");
            prevPosition = chainPosition;
        }
    }

    private String createTestEvent(String eventType, String actorId, String resourceType, String resourceId) throws Exception {
        AuditEventCreateRequest request = new AuditEventCreateRequest();
        request.setEventType(eventType);
        request.setActorId(actorId);
        request.setResourceType(resourceType);
        request.setResourceId(resourceId);
        request.setPayload(objectMapper.createObjectNode()
            .put("action", "view")
            .put("outcome", "SUCCESS")
        );

        MvcResult result = mockMvc.perform(
            post("/audit/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
        .andExpect(status().isCreated())
        .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }
}
