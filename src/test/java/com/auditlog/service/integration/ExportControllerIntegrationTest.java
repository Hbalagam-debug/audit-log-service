package com.auditlog.service.integration;

import com.auditlog.service.SpringBootTestSupport;
import com.auditlog.service.config.ExportProperties;
import com.auditlog.service.config.SigningKeyProvider;
import com.auditlog.service.repository.AuditEventRepository;
import com.auditlog.service.service.CanonicalHashService;
import com.auditlog.service.service.ExportService;
import com.auditlog.service.service.ExportVerifier;
import com.auditlog.service.service.RedactionViewService;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Map;
import java.security.KeyPairGenerator;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("ExportController - Integration Tests")
class ExportControllerIntegrationTest extends SpringBootTestSupport {

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ExportService exportService;

    @Autowired
    private ExportVerifier exportVerifier;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private RedactionViewService redactionViewService;

    @Autowired
    private CanonicalHashService canonicalHashService;

    @Autowired
    private ExportProperties exportProperties;

    @Autowired
    private SigningKeyProvider signingKeyProvider;

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

    private String createEvent(String actorId, String resourceType, String resourceId, Map<String, Object> payloadFields) throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payloadFields.forEach((k, v) -> {
            if (v instanceof String) payload.put(k, (String) v);
            else if (v instanceof Integer) payload.put(k, (Integer) v);
            else if (v instanceof Boolean) payload.put(k, (Boolean) v);
            else payload.put(k, v.toString());
        });

        ObjectNode request = objectMapper.createObjectNode();
        request.put("eventType", "TEST_EVENT");
        request.put("actorId", actorId);
        request.put("resourceType", resourceType);
        request.put("resourceId", resourceId);
        request.set("payload", payload);

        MvcResult result = mockMvc.perform(
            post("/audit/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        ).andExpect(status().isCreated()).andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        return response.get("id").asText();
    }

    @Test
    @DisplayName("Export by actorId returns only matching events in ascending order")
    void testExportByActorId() throws Exception {
        createEvent("export-actor", "RESOURCE", "res-1", Map.of("action", "login"));
        createEvent("export-actor", "RESOURCE", "res-2", Map.of("action", "logout"));
        createEvent("other-actor", "RESOURCE", "res-3", Map.of("action", "view"));

        MvcResult result = mockMvc.perform(get("/audit/exports").param("actorId", "export-actor"))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode bundle = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(2, bundle.get("recordCount").asInt());

        ArrayNode records = (ArrayNode) bundle.get("records");
        assertEquals(2, records.size());
        assertEquals("export-actor", records.get(0).get("actorId").asText());
        assertEquals("export-actor", records.get(1).get("actorId").asText());
        assertTrue(records.get(0).get("chainPosition").asLong() < records.get(1).get("chainPosition").asLong());
        assertEquals("REPRODUCIBLE_FROM_EXPORT", records.get(0).get("contentHashVerificationStatus").asText());
        assertEquals("Ed25519", bundle.get("signature").get("algorithm").asText());
    }

    @Test
    @DisplayName("Export by resourceId returns matching event")
    void testExportByResourceId() throws Exception {
        createEvent("actor-1", "RESOURCE", "export-res", Map.of("action", "create"));
        createEvent("actor-2", "RESOURCE", "other-res", Map.of("action", "update"));

        MvcResult result = mockMvc.perform(get("/audit/exports").param("resourceId", "export-res"))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode bundle = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(1, bundle.get("recordCount").asInt());
    }

    @Test
    @DisplayName("Providing both actorId and resourceId returns 400")
    void testBothSelectorsReturns400() throws Exception {
        mockMvc.perform(get("/audit/exports")
            .param("actorId", "actor-1")
            .param("resourceId", "res-1"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Providing neither actorId nor resourceId returns 400")
    void testNeitherSelectorReturns400() throws Exception {
        mockMvc.perform(get("/audit/exports"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("from > to returns 400")
    void testFromToValidation() throws Exception {
        mockMvc.perform(get("/audit/exports")
            .param("actorId", "actor-1")
            .param("from", "2025-01-02T00:00:00Z")
            .param("to", "2025-01-01T00:00:00Z"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Invalid from timestamp returns 400")
    void testInvalidFromTimestampReturns400() throws Exception {
        mockMvc.perform(get("/audit/exports")
            .param("actorId", "actor-1")
            .param("from", "not-a-timestamp"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Archived events excluded by default")
    void testArchivedExcludedByDefault() throws Exception {
        String eventId = createEvent("archive-actor", "RESOURCE", "res-1", Map.of("action", "test"));
        jdbcTemplate.update("UPDATE audit_events SET is_archived = TRUE WHERE id = ?", eventId);

        MvcResult result = mockMvc.perform(get("/audit/exports")
            .param("actorId", "archive-actor"))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode bundle = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(0, bundle.get("recordCount").asInt());
    }

    @Test
    @DisplayName("Include archived events when includeArchived=true")
    void testIncludeArchivedTrue() throws Exception {
        String eventId = createEvent("archive-actor2", "RESOURCE", "res-2", Map.of("action", "test"));
        jdbcTemplate.update("UPDATE audit_events SET is_archived = TRUE WHERE id = ?", eventId);

        MvcResult result = mockMvc.perform(get("/audit/exports")
            .param("actorId", "archive-actor2")
            .param("includeArchived", "true"))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode bundle = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(1, bundle.get("recordCount").asInt());
    }

    @Test
    @DisplayName("Exported records are in ascending chainPosition order")
    void testAscendingOrder() throws Exception {
        createEvent("order-actor", "RESOURCE", "res-a", Map.of("seq", "1"));
        createEvent("order-actor", "RESOURCE", "res-b", Map.of("seq", "2"));
        createEvent("order-actor", "RESOURCE", "res-c", Map.of("seq", "3"));

        MvcResult result = mockMvc.perform(get("/audit/exports")
            .param("actorId", "order-actor"))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode bundle = objectMapper.readTree(result.getResponse().getContentAsString());
        ArrayNode records = (ArrayNode) bundle.get("records");
        assertEquals(3, records.size());

        long prev = Long.MIN_VALUE;
        for (JsonNode record : records) {
            long pos = record.get("chainPosition").asLong();
            assertTrue(pos > prev, "Expected ascending chainPosition order");
            prev = pos;
        }
    }

    @Test
    @DisplayName("Same unsigned bundle content produces the same verifiable Ed25519 signature")
    void testDeterministicDigestWithFixedClock() throws Exception {
        createEvent("deterministic-actor", "RESOURCE", "res-1", Map.of("action", "create"));

        Clock fixedClock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneId.of("UTC"));
        ExportService fixedClockExportService = new ExportService(
            auditEventRepository,
            redactionViewService,
            canonicalHashService,
            exportProperties,
            signingKeyProvider,
            fixedClock
        );
        JsonNode bundle1 = fixedClockExportService.export("deterministic-actor", null, null, null, false);
        JsonNode bundle2 = fixedClockExportService.export("deterministic-actor", null, null, null, false);

        assertEquals(bundle1.get("recordsDigest").asText(), bundle2.get("recordsDigest").asText());
        assertEquals(bundle1.get("bundleDigest").asText(), bundle2.get("bundleDigest").asText());
        assertEquals("2024-01-01T00:00:00Z", bundle1.get("generatedAt").asText());
        assertEquals(
            bundle1.get("signature").get("value").asText(),
            bundle2.get("signature").get("value").asText()
        );
        assertTrue(exportVerifier.verify(bundle1.toString()).valid());
    }

    @Test
    @DisplayName("Valid Ed25519 signed export verifies successfully")
    void testSignedExportVerifiesSuccessfully() throws Exception {
        createEvent("signed-actor", "RESOURCE", "res-1", Map.of("action", "create"));

        MvcResult result = mockMvc.perform(get("/audit/exports")
            .param("actorId", "signed-actor"))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode bundle = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode signature = bundle.get("signature");

        assertEquals("Ed25519", signature.get("algorithm").asText());
        assertEquals(TEST_EXPORT_SIGNING_KEY_ID, signature.get("keyId").asText());
        assertEquals(TEST_EXPORT_SIGNING_PUBLIC_KEY_BASE64, signature.get("publicKey").asText());

        ExportVerifier.VerificationResult verificationResult = exportVerifier.verify(bundle.toString());
        assertTrue(verificationResult.valid());
        assertTrue(verificationResult.recordsDigestValid());
        assertTrue(verificationResult.bundleDigestValid());
        assertTrue(verificationResult.signatureValid());
        assertEquals(TEST_EXPORT_SIGNING_KEY_ID, verificationResult.keyId());
    }

    @Test
    @DisplayName("Signature value is Base64 and decodes to the Ed25519 signature length")
    void testSignatureIsBase64AndExpectedLength() throws Exception {
        createEvent("signature-length-actor", "RESOURCE", "res-1", Map.of("action", "create"));

        MvcResult result = mockMvc.perform(get("/audit/exports")
            .param("actorId", "signature-length-actor"))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode bundle = objectMapper.readTree(result.getResponse().getContentAsString());
        byte[] signatureBytes = Base64.getDecoder().decode(bundle.get("signature").get("value").asText());
        assertEquals(64, signatureBytes.length);
    }

    @Test
    @DisplayName("Modified manifest field fails bundle verification")
    void testTamperDetectionChangedValue() throws Exception {
        createEvent("tamper-actor", "RESOURCE", "res-1", Map.of("action", "create"));

        MvcResult result = mockMvc.perform(get("/audit/exports")
            .param("actorId", "tamper-actor"))
            .andExpect(status().isOk())
            .andReturn();

        String bundleJson = result.getResponse().getContentAsString();
        ObjectNode tampered = (ObjectNode) objectMapper.readTree(bundleJson);
        tampered.put("generatedAt", "tampered-value");

        ExportVerifier.VerificationResult vr = exportVerifier.verify(tampered.toString());
        assertFalse(vr.valid());
        assertTrue(vr.recordsDigestValid());
        assertFalse(vr.bundleDigestValid());
        assertTrue(vr.signatureValid());
    }

    @Test
    @DisplayName("Modified record fails recordsDigest and bundle verification")
    void testTamperDetectionChangedRecord() throws Exception {
        createEvent("tamper-record-actor", "RESOURCE", "res-1", Map.of("action", "create"));

        MvcResult result = mockMvc.perform(get("/audit/exports")
            .param("actorId", "tamper-record-actor"))
            .andExpect(status().isOk())
            .andReturn();

        String bundleJson = result.getResponse().getContentAsString();
        ObjectNode tampered = (ObjectNode) objectMapper.readTree(bundleJson);
        ArrayNode records = (ArrayNode) tampered.get("records");
        ((ObjectNode) records.get(0)).put("contentHash", "tampered-hash");

        ExportVerifier.VerificationResult vr = exportVerifier.verify(tampered.toString());
        assertFalse(vr.valid());
        assertFalse(vr.recordsDigestValid());
        assertFalse(vr.bundleDigestValid());
        assertTrue(vr.signatureValid());
    }

    @Test
    @DisplayName("Modified signature fails signature verification")
    void testModifiedSignatureFailsVerification() throws Exception {
        createEvent("tamper-signature-actor", "RESOURCE", "res-1", Map.of("action", "create"));

        MvcResult result = mockMvc.perform(get("/audit/exports")
            .param("actorId", "tamper-signature-actor"))
            .andExpect(status().isOk())
            .andReturn();

        ObjectNode tampered = (ObjectNode) objectMapper.readTree(result.getResponse().getContentAsString());
        ((ObjectNode) tampered.get("signature")).put(
            "value",
            Base64.getEncoder().encodeToString(new byte[64])
        );

        ExportVerifier.VerificationResult vr = exportVerifier.verify(tampered.toString());
        assertFalse(vr.valid());
        assertTrue(vr.recordsDigestValid());
        assertTrue(vr.bundleDigestValid());
        assertFalse(vr.signatureValid());
    }

    @Test
    @DisplayName("Changing keyId fails verification against the trusted key configuration")
    void testModifiedKeyIdFailsVerification() throws Exception {
        createEvent("tamper-keyid-actor", "RESOURCE", "res-1", Map.of("action", "create"));

        MvcResult result = mockMvc.perform(get("/audit/exports")
            .param("actorId", "tamper-keyid-actor"))
            .andExpect(status().isOk())
            .andReturn();

        ObjectNode tampered = (ObjectNode) objectMapper.readTree(result.getResponse().getContentAsString());
        ((ObjectNode) tampered.get("signature")).put("keyId", "different-key-id");

        ExportVerifier.VerificationResult vr = exportVerifier.verify(tampered.toString());
        assertFalse(vr.valid());
        assertFalse(vr.signatureValid());
        assertTrue(vr.errors().stream().anyMatch(error -> error.contains("Untrusted signature keyId")));
    }

    @Test
    @DisplayName("Verification with a different trusted public key fails")
    void testWrongPublicKeyFailsVerification() throws Exception {
        createEvent("wrong-public-key-actor", "RESOURCE", "res-1", Map.of("action", "create"));

        MvcResult result = mockMvc.perform(get("/audit/exports")
            .param("actorId", "wrong-public-key-actor"))
            .andExpect(status().isOk())
            .andReturn();

        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("Ed25519");
        String wrongPublicKey = Base64.getEncoder().encodeToString(keyPairGenerator.generateKeyPair().getPublic().getEncoded());
        ExportVerifier wrongKeyVerifier = new ExportVerifier(canonicalHashService, TEST_EXPORT_SIGNING_KEY_ID, wrongPublicKey);

        ExportVerifier.VerificationResult vr = wrongKeyVerifier.verify(result.getResponse().getContentAsString());
        assertFalse(vr.valid());
        assertFalse(vr.signatureValid());
    }

    @Test
    @DisplayName("Unsupported signature algorithm fails cleanly")
    void testUnsupportedAlgorithmFailsCleanly() throws Exception {
        createEvent("unsupported-algorithm-actor", "RESOURCE", "res-1", Map.of("action", "create"));

        MvcResult result = mockMvc.perform(get("/audit/exports")
            .param("actorId", "unsupported-algorithm-actor"))
            .andExpect(status().isOk())
            .andReturn();

        ObjectNode tampered = (ObjectNode) objectMapper.readTree(result.getResponse().getContentAsString());
        ((ObjectNode) tampered.get("signature")).put("algorithm", "RSA");

        ExportVerifier.VerificationResult vr = exportVerifier.verify(tampered.toString());
        assertFalse(vr.valid());
        assertFalse(vr.signatureValid());
        assertTrue(vr.errors().stream().anyMatch(error -> error.contains("Unsupported signature.algorithm")));
    }

    @Test
    @DisplayName("Redacted events: exported payload does not leak original plaintext, redacted=true")
    void testLegacyRedactedFieldsDoNotLeakPlaintext() throws Exception {
        String eventId = createEvent("redact-export-actor", "RESOURCE", "res-1",
            Map.of("ipAddress", "192.168.1.100", "action", "login"));

        String redactionBody = "{" +
            "\"jsonPointers\":[\"/ipAddress\"]," +
            "\"reasonCode\":\"PRIVACY_REQUEST\"," +
            "\"approvalRef\":\"CHG-2026-08-11-04\"," +
            "\"requestedBy\":\"privacy-user\"," +
            "\"approvedBy\":\"privacy-manager\"" +
            "}";
        mockMvc.perform(post("/audit/events/{id}/redactions", eventId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(redactionBody))
            .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(get("/audit/exports")
            .param("actorId", "redact-export-actor"))
            .andExpect(status().isOk())
            .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertFalse(responseBody.contains("192.168.1.100"), "Exported bundle must not contain original IP");

        JsonNode bundle = objectMapper.readTree(responseBody);
        ArrayNode records = (ArrayNode) bundle.get("records");
        boolean anyRedacted = false;
        for (JsonNode record : records) {
            if (record.get("redacted").asBoolean()) {
                anyRedacted = true;
                assertEquals(
                    "LIMITED_BY_EXPORTED_PRESENTATION",
                    record.get("contentHashVerificationStatus").asText()
                );
                assertTrue(record.get("contentHashVerificationNote").asText().contains("stored contentHash"));
            }
        }
        assertTrue(anyRedacted, "At least one record should be marked redacted");
        assertTrue(bundle.get("redactedRecordsPresent").asBoolean());
    }

    @Test
    @DisplayName("Destroyed encrypted fields do not leak plaintext or key material")
    void testDestroyedEncryptedFieldsDoNotLeakPlaintextOrKeyMaterial() throws Exception {
        String eventId = createEvent("encrypted-export-actor", "RESOURCE", "resource-1",
            Map.of("accountNumber", "123456789", "action", "open"));

        mockMvc.perform(post("/audit/events/{id}/redactions", eventId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "jsonPointers", java.util.List.of("/accountNumber"),
                "reasonCode", "PRIVACY_REQUEST",
                "approvalRef", "CHG-2026-08-11-05",
                "requestedBy", "privacy-user",
                "approvedBy", "privacy-manager"
            ))))
            .andExpect(status().isCreated());

        String keyRef = jdbcTemplate.queryForObject(
            "SELECT key_ref FROM audit_event_encryption_keys WHERE event_id = ?",
            String.class,
            eventId
        );

        MvcResult actorExportResult = mockMvc.perform(get("/audit/exports")
            .param("actorId", "encrypted-export-actor"))
            .andExpect(status().isOk())
            .andReturn();

        String actorExportBody = actorExportResult.getResponse().getContentAsString();
        assertFalse(actorExportBody.contains("123456789"));
        assertFalse(actorExportBody.contains(keyRef));
        assertFalse(actorExportBody.contains("$encrypted"));
        assertFalse(actorExportBody.contains("wrapped_dek"));
        assertFalse(actorExportBody.contains("wrap_nonce"));

        JsonNode actorBundle = objectMapper.readTree(actorExportBody);
        JsonNode redactedRecord = actorBundle.get("records").get(0);
        assertEquals("[REDACTED]", redactedRecord.get("payload").get("accountNumber").asText());
        assertEquals("LIMITED_BY_EXPORTED_PRESENTATION", redactedRecord.get("contentHashVerificationStatus").asText());

        MvcResult certificateExportResult = mockMvc.perform(get("/audit/exports")
            .param("resourceId", eventId))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode certificateBundle = objectMapper.readTree(certificateExportResult.getResponse().getContentAsString());
        JsonNode certificateRecord = certificateBundle.get("records").get(0);
        assertEquals("REDACTION_APPLIED", certificateRecord.get("eventType").asText());
        assertFalse(certificateRecord.get("payload").has("keyRefs"));
        assertFalse(certificateRecord.get("payload").has("keyStatus"));
        assertFalse(certificateRecord.get("payload").has("encryptionVersion"));
        assertEquals("LIMITED_BY_EXPORTED_PRESENTATION", certificateRecord.get("contentHashVerificationStatus").asText());
        assertTrue(certificateRecord.get("contentHashVerificationNote").asText().contains("omitted"));
    }

    @Test
    @DisplayName("Empty export for non-existent actorId returns valid bundle with recordCount=0")
    void testEmptyExport() throws Exception {
        MvcResult result = mockMvc.perform(get("/audit/exports")
            .param("actorId", "no-such-actor-xyz"))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode bundle = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(0, bundle.get("recordCount").asInt());
        assertEquals(0, ((ArrayNode) bundle.get("records")).size());
        assertNotNull(bundle.get("recordsDigest").asText());
        assertFalse(bundle.get("recordsDigest").asText().isBlank());
        assertNotNull(bundle.get("bundleDigest").asText());
        assertFalse(bundle.get("bundleDigest").asText().isBlank());
        assertTrue(bundle.get("firstChainPosition").isNull());
        assertTrue(bundle.get("lastChainPosition").isNull());
    }

    @Test
    @DisplayName("Exceeding max-records (100 in test) returns 413")
    void testMaxSizeRejection() throws Exception {
        for (int i = 0; i < 101; i++) {
            createEvent("overload-actor", "RESOURCE", "res-" + i, Map.of("seq", String.valueOf(i)));
        }

        mockMvc.perform(get("/audit/exports").param("actorId", "overload-actor"))
            .andExpect(status().isPayloadTooLarge());
    }

    @Test
    @DisplayName("Source-controlled configuration and docs do not leak the test export signing keys")
    void testSourceControlledFilesDoNotLeakTestSigningKeys() throws Exception {
        for (String relativePath : java.util.List.of(
            "src\\main\\resources\\application.yml",
            "src\\test\\resources\\application-test.yml",
            "README.md",
            "docs\\architecture\\scenario-b-design.md",
            "docs\\ai\\usage-log.md"
        )) {
            String content = Files.readString(Path.of(relativePath), StandardCharsets.UTF_8);
            assertFalse(content.contains(TEST_EXPORT_SIGNING_PRIVATE_KEY_BASE64), relativePath);
            assertFalse(content.contains(TEST_EXPORT_SIGNING_PUBLIC_KEY_BASE64), relativePath);
        }
    }
}
