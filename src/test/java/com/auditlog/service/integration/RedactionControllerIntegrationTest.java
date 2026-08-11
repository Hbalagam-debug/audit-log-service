package com.auditlog.service.integration;

import com.auditlog.service.SpringBootTestSupport;
import com.auditlog.service.api.dto.AuditEventCreateRequest;
import com.auditlog.service.api.dto.QueryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("RedactionController - Integration Tests")
class RedactionControllerIntegrationTest extends SpringBootTestSupport {
    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        jdbcTemplate.execute("DELETE FROM audit_event_redactions");
        jdbcTemplate.execute("DELETE FROM audit_events");
        jdbcTemplate.execute("DELETE FROM retention_runs");
    }

    @Test
    @DisplayName("POST /audit/events/{id}/redactions applies overlay and appends certificate")
    void testRedactionCreatesOverlayAndMasksQueryResponse() throws Exception {
        String eventId = createEvent(Map.of(
            "ipAddress", "203.0.113.10",
            "device", Map.of("id", "device-123")
        ));

        String requestBody = "{" +
            "\"jsonPointers\":[\"/ipAddress\",\"/device/id\"]," +
            "\"reasonCode\":\"PRIVACY_REQUEST\"," +
            "\"approvalRef\":\"CHG-2026-08-10-02\"," +
            "\"requestedBy\":\"privacy-user\"," +
            "\"approvedBy\":\"privacy-manager\"" +
            "}";

        mockMvc.perform(post("/audit/events/{id}/redactions", eventId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.eventId").value(eventId))
            .andExpect(jsonPath("$.appliedPointers[0]").value("/device/id"))
            .andExpect(jsonPath("$.appliedPointers[1]").value("/ipAddress"))
            .andExpect(jsonPath("$.status").value("APPLIED"));

        MvcResult queryResult = mockMvc.perform(get("/audit/events"))
            .andExpect(status().isOk())
            .andReturn();

        QueryResponse response = objectMapper.readValue(queryResult.getResponse().getContentAsString(), QueryResponse.class);
        assertEquals(2, response.items().size());
        // Find the original event (not the certificate event)
        var originalEvent = response.items().stream()
            .filter(e -> e.id().equals(eventId))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Original event not found in query response"));
        assertTrue(originalEvent.redacted());
        assertEquals("[REDACTED]", originalEvent.payload().get("ipAddress").asText());
        assertEquals("[REDACTED]", originalEvent.payload().get("device").get("id").asText());

        String storedPayload = jdbcTemplate.queryForObject("SELECT payload_json FROM audit_events WHERE id = ?", String.class, eventId);
        assertTrue(storedPayload.contains("203.0.113.10"));
        assertTrue(storedPayload.contains("device-123"));

        MvcResult verifyResult = mockMvc.perform(get("/audit/verify"))
            .andExpect(status().isOk())
            .andReturn();
        assertTrue(verifyResult.getResponse().getContentAsString().contains("\"intact\":true"));

        int certificateCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_events WHERE event_type = 'REDACTION_APPLIED'", Integer.class);
        assertEquals(1, certificateCount);
        int overlayCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_event_redactions WHERE event_id = ?", Integer.class, eventId);
        assertEquals(2, overlayCount);
    }

    @Test
    @DisplayName("Repeating the same redaction returns 200 and does not duplicate overlays")
    void testRepeatingTheSameRedactionIsIdempotent() throws Exception {
        String eventId = createEvent(Map.of("ipAddress", "203.0.113.10"));
        String requestBody = "{" +
            "\"jsonPointers\":[\"/ipAddress\"]," +
            "\"reasonCode\":\"PRIVACY_REQUEST\"," +
            "\"approvalRef\":\"CHG-2026-08-10-02\"," +
            "\"requestedBy\":\"privacy-user\"," +
            "\"approvedBy\":\"privacy-manager\"" +
            "}";

        mockMvc.perform(post("/audit/events/{id}/redactions", eventId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/audit/events/{id}/redactions", eventId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.appliedPointers").isEmpty())
            .andExpect(jsonPath("$.alreadyRedactedPointers[0]").value("/ipAddress"));

        int overlayCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_event_redactions WHERE event_id = ?", Integer.class, eventId);
        assertEquals(1, overlayCount);
        int certificateCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_events WHERE event_type = 'REDACTION_APPLIED'", Integer.class);
        assertEquals(1, certificateCount);
    }

    @Test
    @DisplayName("Malformed pointers return HTTP 400")
    void testMalformedPointerReturns400() throws Exception {
        String eventId = createEvent(Map.of("ipAddress", "203.0.113.10"));
        String requestBody = "{" +
            "\"jsonPointers\":[\"/ipAddress~2\"]," +
            "\"reasonCode\":\"PRIVACY_REQUEST\"," +
            "\"approvalRef\":\"CHG-2026-08-10-02\"," +
            "\"requestedBy\":\"privacy-user\"," +
            "\"approvedBy\":\"privacy-manager\"" +
            "}";

        mockMvc.perform(post("/audit/events/{id}/redactions", eventId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("Missing approval data returns HTTP 400")
    void testMissingApprovalDataReturns400() throws Exception {
        String eventId = createEvent(Map.of("ipAddress", "203.0.113.10"));
        String requestBody = "{" +
            "\"jsonPointers\":[\"/ipAddress\"]," +
            "\"reasonCode\":\"PRIVACY_REQUEST\"," +
            "\"requestedBy\":\"privacy-user\"" +
            "}";

        mockMvc.perform(post("/audit/events/{id}/redactions", eventId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    private String createEvent(Map<String, Object> payload) throws Exception {
        AuditEventCreateRequest request = new AuditEventCreateRequest();
        request.setEventType("USER_LOGIN");
        request.setActorId("user-1");
        request.setResourceType("ACCOUNT");
        request.setResourceId("account-1");
        request.setPayload(objectMapper.valueToTree(payload));

        MvcResult result = mockMvc.perform(post("/audit/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }
}
