package com.auditlog.service.integration;

import com.auditlog.service.SpringBootTestSupport;
import com.auditlog.service.api.dto.AuditEventCreateRequest;
import com.auditlog.service.api.dto.QueryResponse;
import com.auditlog.service.api.dto.VerificationResultResponse;
import com.auditlog.service.service.CanonicalHashService;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("EncryptionController - Integration Tests")
class EncryptionControllerIntegrationTest extends SpringBootTestSupport {
    private static final String TEST_MASTER_KEY_BASE64 = "MDEyMzQ1Njc4OUFCQ0RFRjAxMjM0NTY3ODlBQkNERUY=";

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CanonicalHashService canonicalHashService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        jdbcTemplate.execute("DELETE FROM audit_event_redactions");
        jdbcTemplate.execute("DELETE FROM audit_event_encryption_keys");
        jdbcTemplate.execute("DELETE FROM audit_events");
        jdbcTemplate.execute("DELETE FROM retention_runs");
    }

    @Test
    @DisplayName("Encrypted fields persist as ciphertext but are decrypted in API responses and verification remains intact")
    void testEncryptedCreateAndQueryFlow() throws Exception {
        String eventId = createEvent(Map.of(
            "accountNumber", "123456789",
            "personalIdentifier", "PI-001",
            "ipAddress", "203.0.113.10"
        ));

        String storedPayload = jdbcTemplate.queryForObject(
            "SELECT payload_json FROM audit_events WHERE id = ?",
            String.class,
            eventId
        );
        assertNotNull(storedPayload);
        assertFalse(storedPayload.contains("123456789"));
        assertFalse(storedPayload.contains("PI-001"));
        assertTrue(storedPayload.contains("$encrypted"));
        assertTrue(storedPayload.contains("203.0.113.10"));

        QueryResponse queryResponse = queryEvents();
        var event = queryResponse.items().stream()
            .filter(item -> item.id().equals(eventId))
            .findFirst()
            .orElseThrow();
        assertEquals("123456789", event.payload().get("accountNumber").asText());
        assertEquals("PI-001", event.payload().get("personalIdentifier").asText());
        assertEquals("203.0.113.10", event.payload().get("ipAddress").asText());
        assertFalse(event.redacted());

        JsonNode storedPayloadNode = objectMapper.readTree(storedPayload);
        String expectedContentHash = canonicalHashService.computeContentHash(
            event.eventType(),
            event.actorId(),
            event.resourceType(),
            event.resourceId(),
            storedPayloadNode,
            event.timestamp()
        );
        assertEquals(expectedContentHash, event.contentHash());

        VerificationResultResponse verification = verifyChain();
        assertTrue(verification.intact());
    }

    @Test
    @DisplayName("Destroying an encrypted key redacts future reads without changing ciphertext or hashes and remains idempotent")
    void testEncryptedRedactionDestroysKeyMaterialAndPreservesStoredChain() throws Exception {
        String eventId = createEvent(Map.of(
            "accountNumber", "123456789",
            "personalIdentifier", "PI-001",
            "ipAddress", "203.0.113.10"
        ));
        String originalPayloadJson = jdbcTemplate.queryForObject(
            "SELECT payload_json FROM audit_events WHERE id = ?",
            String.class,
            eventId
        );
        String originalContentHash = jdbcTemplate.queryForObject(
            "SELECT content_hash FROM audit_events WHERE id = ?",
            String.class,
            eventId
        );
        String originalChainHash = jdbcTemplate.queryForObject(
            "SELECT chain_hash FROM audit_events WHERE id = ?",
            String.class,
            eventId
        );

        MvcResult redactResult = mockMvc.perform(post("/audit/events/{id}/redactions", eventId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "jsonPointers", java.util.List.of("/accountNumber"),
                    "reasonCode", "PRIVACY_REQUEST",
                    "approvalRef", "CHG-2026-08-11-01",
                    "requestedBy", "privacy-user",
                    "approvedBy", "privacy-manager"
                ))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("APPLIED"))
            .andReturn();

        JsonNode redactionResponse = objectMapper.readTree(redactResult.getResponse().getContentAsString());
        String certificateEventId = redactionResponse.get("redactionCertificateEventId").asText();
        assertTrue(certificateEventId != null && !certificateEventId.isBlank());

        QueryResponse queryResponse = queryEvents();
        var event = queryResponse.items().stream()
            .filter(item -> item.id().equals(eventId))
            .findFirst()
            .orElseThrow();
        assertEquals("[REDACTED]", event.payload().get("accountNumber").asText());
        assertEquals("PI-001", event.payload().get("personalIdentifier").asText());
        assertTrue(event.redacted());
        assertTrue(event.redactedPointers().contains("/accountNumber"));

        assertEquals(originalPayloadJson, jdbcTemplate.queryForObject("SELECT payload_json FROM audit_events WHERE id = ?", String.class, eventId));
        assertEquals(originalContentHash, jdbcTemplate.queryForObject("SELECT content_hash FROM audit_events WHERE id = ?", String.class, eventId));
        assertEquals(originalChainHash, jdbcTemplate.queryForObject("SELECT chain_hash FROM audit_events WHERE id = ?", String.class, eventId));

        Map<String, Object> keyRow = jdbcTemplate.queryForMap(
            "SELECT status, wrapped_dek, wrap_nonce FROM audit_event_encryption_keys WHERE event_id = ? AND encrypted_pointers_json LIKE '%/accountNumber%'",
            eventId
        );
        assertEquals("DESTROYED", keyRow.get("status"));
        assertNull(keyRow.get("wrapped_dek"));
        assertNull(keyRow.get("wrap_nonce"));

        String certificatePayload = jdbcTemplate.queryForObject(
            "SELECT payload_json FROM audit_events WHERE id = ?",
            String.class,
            certificateEventId
        );
        assertFalse(certificatePayload.contains("123456789"));
        assertFalse(certificatePayload.contains("PI-001"));
        assertFalse(certificatePayload.contains(TEST_MASTER_KEY_BASE64));
        assertFalse(certificatePayload.contains("$encrypted"));
        assertFalse(certificatePayload.contains("ciphertext"));

        VerificationResultResponse verification = verifyChain();
        assertTrue(verification.intact());

        mockMvc.perform(post("/audit/events/{id}/redactions", eventId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "jsonPointers", java.util.List.of("/accountNumber"),
                    "reasonCode", "PRIVACY_REQUEST",
                    "approvalRef", "CHG-2026-08-11-01",
                    "requestedBy", "privacy-user",
                    "approvedBy", "privacy-manager"
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ALREADY_REDACTED"));

        int certificateCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_events WHERE event_type = 'REDACTION_APPLIED'",
            Integer.class
        );
        assertEquals(1, certificateCount);
    }

    @Test
    @DisplayName("Mixed encrypted and legacy pointer redaction uses one certificate and ciphertext tampering still breaks verification")
    void testMixedRedactionAndCiphertextTampering() throws Exception {
        String eventId = createEvent(Map.of(
            "accountNumber", "123456789",
            "ipAddress", "203.0.113.10"
        ));

        MvcResult redactResult = mockMvc.perform(post("/audit/events/{id}/redactions", eventId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "jsonPointers", java.util.List.of("/accountNumber", "/ipAddress"),
                    "reasonCode", "PRIVACY_REQUEST",
                    "approvalRef", "CHG-2026-08-11-02",
                    "requestedBy", "privacy-user",
                    "approvedBy", "privacy-manager"
                ))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.appliedPointers[0]").value("/accountNumber"))
            .andExpect(jsonPath("$.appliedPointers[1]").value("/ipAddress"))
            .andReturn();

        String certificateEventId = objectMapper.readTree(redactResult.getResponse().getContentAsString())
            .get("redactionCertificateEventId").asText();
        String certificatePayload = jdbcTemplate.queryForObject(
            "SELECT payload_json FROM audit_events WHERE id = ?",
            String.class,
            certificateEventId
        );
        assertTrue(certificatePayload.contains("\"redactionMode\":\"HYBRID\""));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_event_redactions WHERE event_id = ?", Integer.class, eventId));

        QueryResponse queryResponse = queryEvents();
        var event = queryResponse.items().stream()
            .filter(item -> item.id().equals(eventId))
            .findFirst()
            .orElseThrow();
        assertEquals("[REDACTED]", event.payload().get("accountNumber").asText());
        assertEquals("[REDACTED]", event.payload().get("ipAddress").asText());

        String storedPayload = jdbcTemplate.queryForObject("SELECT payload_json FROM audit_events WHERE id = ?", String.class, eventId);
        JsonNode payloadNode = objectMapper.readTree(storedPayload);
        JsonNode encrypted = payloadNode.get("accountNumber").get("$encrypted");
        String originalCiphertext = encrypted.get("ciphertext").asText();
        encrypted = (JsonNode) encrypted.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) payloadNode.get("accountNumber").get("$encrypted"))
            .put("ciphertext", mutateBase64(originalCiphertext));
        jdbcTemplate.update("UPDATE audit_events SET payload_json = ? WHERE id = ?", payloadNode.toString(), eventId);

        VerificationResultResponse verification = verifyChain();
        assertFalse(verification.intact());
    }

    @Test
    @DisplayName("Source-controlled configuration and docs do not leak the test master key")
    void testSourceControlledFilesDoNotLeakTestKey() throws Exception {
        for (String relativePath : java.util.List.of(
            "src\\main\\resources\\application.yml",
            "src\\test\\resources\\application-test.yml",
            "README.md",
            "docs\\architecture\\scenario-b-design.md",
            "docs\\ai\\usage-log.md"
        )) {
            String content = Files.readString(Path.of(relativePath), StandardCharsets.UTF_8);
            assertFalse(content.contains(TEST_MASTER_KEY_BASE64), relativePath);
        }
    }

    private String createEvent(Map<String, Object> payload) throws Exception {
        AuditEventCreateRequest request = new AuditEventCreateRequest();
        request.setEventType("ACCOUNT_CREATED");
        request.setActorId("user-1");
        request.setResourceType("ACCOUNT");
        request.setResourceId("account-1");
        request.setPayload(objectMapper.valueToTree(payload));

        MvcResult result = mockMvc.perform(post("/audit/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(payload.get("accountNumber"), response.path("payload").path("accountNumber").asText(null));
        return response.get("id").asText();
    }

    private QueryResponse queryEvents() throws Exception {
        MvcResult result = mockMvc.perform(get("/audit/events").contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), QueryResponse.class);
    }

    private VerificationResultResponse verifyChain() throws Exception {
        MvcResult result = mockMvc.perform(get("/audit/verify").contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), VerificationResultResponse.class);
    }

    private String mutateBase64(String value) {
        char replacement = value.charAt(value.length() - 1) == 'A' ? 'B' : 'A';
        return value.substring(0, value.length() - 1) + replacement;
    }
}
