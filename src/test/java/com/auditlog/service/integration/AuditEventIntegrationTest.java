package com.auditlog.service.integration;

import com.auditlog.service.SpringBootTestSupport;
import com.auditlog.service.api.dto.AuditEventCreateRequest;
import com.auditlog.service.api.dto.QueryResponse;
import com.auditlog.service.api.dto.VerificationResultResponse;
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
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("AuditEventController - REST API Integration Tests")
class AuditEventIntegrationTest extends SpringBootTestSupport {
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
    @DisplayName("POST /audit/events creates event with 201 Created")
    void testCreateEventReturns201() throws Exception {
        AuditEventCreateRequest request = new AuditEventCreateRequest();
        request.setEventType("USER_LOGIN");
        request.setActorId("user-123");
        request.setResourceType("ACCOUNT");
        request.setResourceId("account-456");
        request.setPayload(objectMapper.createObjectNode()
            .put("result", "SUCCESS")
            .put("ipAddress", "192.0.2.1")
        );

        mockMvc.perform(
            post("/audit/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").isNotEmpty())
        .andExpect(jsonPath("$.chainPosition").value(1))
        .andExpect(jsonPath("$.eventType").value("USER_LOGIN"))
        .andExpect(jsonPath("$.contentHash").isNotEmpty())
        .andExpect(jsonPath("$.chainHash").isNotEmpty());
    }

    @Test
    @DisplayName("GET /audit/events returns items in chain order")
    void testQueryEventsReturnsItems() throws Exception {
        createTestEvent("EVENT_1", "user-1", "ACCOUNT", "acc-1");
        createTestEvent("EVENT_2", "user-2", "DOCUMENT", "doc-1");

        MvcResult result = mockMvc.perform(
            get("/audit/events")
                .contentType(MediaType.APPLICATION_JSON)
        )
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray())
        .andReturn();

        QueryResponse response = objectMapper.readValue(
            result.getResponse().getContentAsString(),
            QueryResponse.class
        );

        assertEquals(2, response.items().size());
        assertTrue(response.items().get(0).chainPosition() < response.items().get(1).chainPosition());
    }

    @Test
    @DisplayName("GET /audit/events filters by actorId")
    void testQueryEventsFilterByActorId() throws Exception {
        createTestEvent("EVENT_1", "user-1", "ACCOUNT", "acc-1");
        createTestEvent("EVENT_2", "user-2", "DOCUMENT", "doc-1");

        MvcResult result = mockMvc.perform(
            get("/audit/events")
                .param("actorId", "user-1")
                .contentType(MediaType.APPLICATION_JSON)
        )
        .andExpect(status().isOk())
        .andReturn();

        QueryResponse response = objectMapper.readValue(
            result.getResponse().getContentAsString(),
            QueryResponse.class
        );

        assertEquals(1, response.items().size());
        assertEquals("user-1", response.items().get(0).actorId());
    }

    @Test
    @DisplayName("Generated nextCursor retrieves page two with same filters")
    void testGeneratedNextCursorRetrievesSecondPage() throws Exception {
        createTestEvent("EVENT_1", "user-123", "ACCOUNT", "acc-1");
        createTestEvent("EVENT_2", "user-123", "ACCOUNT", "acc-2");

        MvcResult firstPageResult = mockMvc.perform(
            get("/audit/events")
                .param("actorId", "user-123")
                .param("limit", "1")
                .contentType(MediaType.APPLICATION_JSON)
        )
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].resourceId").value("acc-1"))
        .andExpect(jsonPath("$.nextCursor").isNotEmpty())
        .andReturn();

        QueryResponse firstPage = objectMapper.readValue(
            firstPageResult.getResponse().getContentAsString(),
            QueryResponse.class
        );

        MvcResult secondPageResult = mockMvc.perform(
            get("/audit/events")
                .param("actorId", "user-123")
                .param("limit", "1")
                .param("cursor", firstPage.nextCursor())
                .contentType(MediaType.APPLICATION_JSON)
        )
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].resourceId").value("acc-2"))
        .andExpect(jsonPath("$.nextCursor").doesNotExist())
        .andReturn();

        QueryResponse secondPage = objectMapper.readValue(
            secondPageResult.getResponse().getContentAsString(),
            QueryResponse.class
        );

        assertEquals(1, firstPage.items().size());
        assertEquals(1, secondPage.items().size());
        assertNotEquals(firstPage.items().get(0).id(), secondPage.items().get(0).id());
    }

    @Test
    @DisplayName("URL-safe cursor works unchanged on next request")
    void testUrlSafeCursorWorksWithoutClientModification() throws Exception {
        createTestEvent("EVENT_1", "user-123", "ACCOUNT", "acc-1");
        createTestEvent("EVENT_2", "user-123", "ACCOUNT", "acc-2");

        MvcResult firstPageResult = mockMvc.perform(
            get("/audit/events")
                .param("actorId", "user-123")
                .param("limit", "1")
                .contentType(MediaType.APPLICATION_JSON)
        )
        .andExpect(status().isOk())
        .andReturn();

        QueryResponse firstPage = objectMapper.readValue(
            firstPageResult.getResponse().getContentAsString(),
            QueryResponse.class
        );

        assertNotNull(firstPage.nextCursor());
        assertFalse(firstPage.nextCursor().contains("+"));
        assertFalse(firstPage.nextCursor().contains("/"));

        mockMvc.perform(
            get("/audit/events")
                .param("actorId", "user-123")
                .param("limit", "1")
                .param("cursor", firstPage.nextCursor())
                .contentType(MediaType.APPLICATION_JSON)
        )
        .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Malformed cursor returns HTTP 400 INVALID_CURSOR")
    void testMalformedCursorReturns400() throws Exception {
        mockMvc.perform(
            get("/audit/events")
                .param("cursor", "%%%")
                .contentType(MediaType.APPLICATION_JSON)
        )
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
    }

    @Test
    @DisplayName("GET /audit/events with limit=50 returns 200")
    void testQueryEventsWithSupportedLimitReturns200() throws Exception {
        createTestEvent("EVENT_1", "user-1", "ACCOUNT", "acc-1");

        mockMvc.perform(
            get("/audit/events")
                .param("limit", "50")
                .contentType(MediaType.APPLICATION_JSON)
        )
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    @DisplayName("GET /audit/events with limit=100 returns 400")
    void testQueryEventsLimitAboveMaximumReturns400() throws Exception {
        mockMvc.perform(
            get("/audit/events")
                .param("limit", "100")
                .contentType(MediaType.APPLICATION_JSON)
        )
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
        .andExpect(jsonPath("$.message").value("limit must be between 1 and 50"));
    }

    @Test
    @DisplayName("GET /audit/events with non-numeric limit returns 400 not 500")
    void testQueryEventsInvalidLimitDoesNotReturn500() throws Exception {
        mockMvc.perform(
            get("/audit/events")
                .param("limit", "abc")
                .contentType(MediaType.APPLICATION_JSON)
        )
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
        .andExpect(jsonPath("$.message").value("limit must be a whole number"));
    }

    @Test
    @DisplayName("COMPLIANCE_REPORT_GENERATED with optional null fields does not break /audit/events query")
    void testComplianceCertificateEventWithNullOptionalFieldsDoesNotBreakQuery() throws Exception {
        createTestEvent("EVENT_1", "user-1", "ACCOUNT", "acc-1");

        ObjectNode certPayload = objectMapper.createObjectNode();
        certPayload.put("approvalRef", "APP-REG-001");
        certPayload.put("recordCount", 0);
        certPayload.put("bundleDigest", "d".repeat(64));
        certPayload.put("criteriaDigest", "c".repeat(64));
        certPayload.put("generatedAt", "2026-08-11T00:00:00Z");
        certPayload.putNull("reasonCode");
        certPayload.putNull("signingKeyId");

        createEventWithPayload(
            "COMPLIANCE_REPORT_GENERATED",
            "system:compliance-report-service",
            "COMPLIANCE_REPORT",
            "APP-REG-001",
            certPayload
        );

        MvcResult result = mockMvc.perform(
            get("/audit/events")
                .param("limit", "50")
                .contentType(MediaType.APPLICATION_JSON)
        )
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray())
        .andReturn();

        QueryResponse response = objectMapper.readValue(
            result.getResponse().getContentAsString(),
            QueryResponse.class
        );
        assertTrue(
            response.items().stream().anyMatch(item -> "COMPLIANCE_REPORT_GENERATED".equals(item.eventType()))
        );
    }

    @Test
    @DisplayName("limit=1 over two events returns one item per page with no duplicates")
    void testLimitOnePaginationHasNoDuplicates() throws Exception {
        createTestEvent("EVENT_1", "user-123", "ACCOUNT", "acc-1");
        createTestEvent("EVENT_2", "user-123", "ACCOUNT", "acc-2");

        MvcResult firstPageResult = mockMvc.perform(
            get("/audit/events")
                .param("actorId", "user-123")
                .param("limit", "1")
                .contentType(MediaType.APPLICATION_JSON)
        )
        .andExpect(status().isOk())
        .andReturn();

        QueryResponse firstPage = objectMapper.readValue(
            firstPageResult.getResponse().getContentAsString(),
            QueryResponse.class
        );

        MvcResult secondPageResult = mockMvc.perform(
            get("/audit/events")
                .param("actorId", "user-123")
                .param("limit", "1")
                .param("cursor", firstPage.nextCursor())
                .contentType(MediaType.APPLICATION_JSON)
        )
        .andExpect(status().isOk())
        .andReturn();

        QueryResponse secondPage = objectMapper.readValue(
            secondPageResult.getResponse().getContentAsString(),
            QueryResponse.class
        );

        assertEquals(1, firstPage.items().size());
        assertEquals(1, secondPage.items().size());
        assertNotEquals(firstPage.items().get(0).id(), secondPage.items().get(0).id());
        assertNull(secondPage.nextCursor());
    }

    @Test
    @DisplayName("GET /audit/verify returns intact=true for valid chain")
    void testVerifyChainIntact() throws Exception {
        createTestEvent("EVENT_1", "user-1", "ACCOUNT", "acc-1");

        MvcResult result = mockMvc.perform(
            get("/audit/verify")
                .contentType(MediaType.APPLICATION_JSON)
        )
        .andExpect(status().isOk())
        .andReturn();

        VerificationResultResponse response = objectMapper.readValue(
            result.getResponse().getContentAsString(),
            VerificationResultResponse.class
        );

        assertTrue(response.intact());
        assertEquals(1, response.recordsChecked());
        assertEquals("Audit chain is intact", response.message());
    }

    @Test
    @DisplayName("POST /audit/events with invalid payload returns 400")
    void testCreateEventWithInvalidPayload() throws Exception {
        AuditEventCreateRequest request = new AuditEventCreateRequest();
        request.setEventType("TEST");
        request.setActorId("user-1");
        request.setResourceType("RESOURCE");
        request.setResourceId("res-1");
        request.setPayload(null);

        mockMvc.perform(
            post("/audit/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
        .andExpect(status().isBadRequest());
    }

    private void createTestEvent(String eventType, String actorId, String resourceType, String resourceId) throws Exception {
        createEventWithPayload(
            eventType,
            actorId,
            resourceType,
            resourceId,
            objectMapper.createObjectNode().put("data", "test")
        );
    }

    private void createEventWithPayload(
        String eventType,
        String actorId,
        String resourceType,
        String resourceId,
        ObjectNode payload
    ) throws Exception {
        AuditEventCreateRequest request = new AuditEventCreateRequest();
        request.setEventType(eventType);
        request.setActorId(actorId);
        request.setResourceType(resourceType);
        request.setResourceId(resourceId);
        request.setPayload(payload);

        mockMvc.perform(
            post("/audit/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
        .andExpect(status().isCreated());
    }
}
