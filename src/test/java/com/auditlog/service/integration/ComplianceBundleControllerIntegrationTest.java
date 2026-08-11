package com.auditlog.service.integration;

import com.auditlog.service.SpringBootTestSupport;
import com.auditlog.service.service.ExportVerifier;
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
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("ComplianceBundle - Scenario C H2 Integration Tests")
class ComplianceBundleControllerIntegrationTest extends SpringBootTestSupport {

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ExportVerifier exportVerifier;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        jdbcTemplate.execute("DELETE FROM audit_event_redactions");
        jdbcTemplate.execute("DELETE FROM audit_event_encryption_keys");
        jdbcTemplate.execute("DELETE FROM audit_events");
    }

    // -----------------------------------------------------------------------
    // Success path
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("POST /bundle returns 201 with signed bundle containing matching access events")
    void testSuccessfulSignedBundle() throws Exception {
        String now = Instant.now().toString();
        String later = Instant.now().plus(1, ChronoUnit.DAYS).toString();

        createAccessEvent("CLIENT_ACCOUNT_DATA_VIEWED",   "user-1", "acc-1");
        createAccessEvent("CLIENT_ACCOUNT_DATA_SEARCHED", "user-1", "acc-1");
        createAccessEvent("USER_LOGIN", "user-1", "acc-1"); // excluded — not in taxonomy

        ObjectNode req = bundleRequest("acc-1", null, now, later, "APPROVAL-001", null);

        MvcResult result = performBundle(req, 201);
        JsonNode bundle = objectMapper.readTree(result.getResponse().getContentAsString());

        assertEquals("1", bundle.get("manifestVersion").asText());
        assertEquals("COMPLIANCE_ACCESS_REPORT", bundle.get("reportType").asText());
        assertEquals(2, bundle.get("recordCount").asInt()); // only the 2 access-type events
        assertNotNull(bundle.get("bundleDigest").asText());

        // Signature block present with required fields
        JsonNode sig = bundle.get("signature");
        assertNotNull(sig);
        assertEquals("Ed25519", sig.get("algorithm").asText());
        assertNotNull(sig.get("keyId").asText());
        assertFalse(sig.get("value").asText().isEmpty());
        assertFalse(sig.get("publicKey").asText().isEmpty());
    }

    @Test
    @DisplayName("Offline ExportVerifier can verify the compliance bundle signature")
    void testOfflineSignatureVerification() throws Exception {
        String now = Instant.now().toString();
        String later = Instant.now().plus(1, ChronoUnit.DAYS).toString();

        createAccessEvent("CLIENT_ACCOUNT_DATA_VIEWED", "user-1", "acc-1");

        ObjectNode req = bundleRequest("acc-1", null, now, later, "APPROVAL-VERIFY-001", null);
        MvcResult result = performBundle(req, 201);
        String bundleJson = result.getResponse().getContentAsString();

        ExportVerifier.VerificationResult verification = exportVerifier.verify(bundleJson);

        assertTrue(verification.valid(), "Bundle must be fully valid: " + verification.errors());
        assertTrue(verification.recordsDigestValid());
        assertTrue(verification.bundleDigestValid());
        assertTrue(verification.signatureValid());
        assertTrue(verification.errors().isEmpty());
    }

    @Test
    @DisplayName("Tampered bundle records cause verification failure")
    void testTamperedBundleVerificationFailure() throws Exception {
        String now = Instant.now().toString();
        String later = Instant.now().plus(1, ChronoUnit.DAYS).toString();

        createAccessEvent("CLIENT_ACCOUNT_DATA_EXPORTED", "user-1", "acc-1");

        ObjectNode req = bundleRequest("acc-1", null, now, later, "APPROVAL-TAMPER-001", null);
        MvcResult result = performBundle(req, 201);

        // Tamper: alter the first record's eventType
        ObjectNode bundle = (ObjectNode) objectMapper.readTree(result.getResponse().getContentAsString());
        ObjectNode firstRecord = (ObjectNode) bundle.get("records").get(0);
        firstRecord.put("eventType", "TAMPERED_EVENT");

        ExportVerifier.VerificationResult verification = exportVerifier.verify(objectMapper.writeValueAsString(bundle));

        assertFalse(verification.valid());
        assertFalse(verification.recordsDigestValid());
    }

    @Test
    @DisplayName("Deterministic records digest: identical inputs produce same digest")
    void testDeterministicRecordsDigest() throws Exception {
        String now = Instant.now().toString();
        String later = Instant.now().plus(1, ChronoUnit.DAYS).toString();

        createAccessEvent("CLIENT_ACCOUNT_DATA_VIEWED", "user-1", "acc-1");

        ObjectNode req = bundleRequest("acc-1", null, now, later, "APPROVAL-DET-A", null);
        MvcResult first  = performBundle(req, 201);

        // Second bundle with same data (cert event from first call won't match taxonomy, excluded automatically)
        ObjectNode req2 = bundleRequest("acc-1", null, now, later, "APPROVAL-DET-B", null);
        MvcResult second = performBundle(req2, 201);

        String digestA = objectMapper.readTree(first.getResponse().getContentAsString()).get("recordsDigest").asText();
        String digestB = objectMapper.readTree(second.getResponse().getContentAsString()).get("recordsDigest").asText();

        assertEquals(digestA, digestB, "Identical record sets must produce identical recordsDigests");
    }

    @Test
    @DisplayName("COMPLIANCE_REPORT_GENERATED cert event is excluded from bundle records")
    void testCertificateEventExcludedFromBundle() throws Exception {
        String now = Instant.now().toString();
        String later = Instant.now().plus(1, ChronoUnit.DAYS).toString();

        createAccessEvent("CLIENT_ACCOUNT_DATA_VIEWED", "user-1", "acc-1");

        // First call appends a cert event
        performBundle(bundleRequest("acc-1", null, now, later, "APPROVAL-EXCL-A", null), 201);

        // Second call — cert event from first call is present in DB but must not appear in records
        MvcResult result = performBundle(bundleRequest("acc-1", null, now, later, "APPROVAL-EXCL-B", null), 201);
        JsonNode bundle = objectMapper.readTree(result.getResponse().getContentAsString());

        // Only the original access event should appear; cert events are excluded
        assertEquals(1, bundle.get("recordCount").asInt());
        bundle.get("records").forEach(r ->
            assertNotEquals("COMPLIANCE_REPORT_GENERATED", r.get("eventType").asText())
        );
    }

    @Test
    @DisplayName("COMPLIANCE_REPORT_GENERATED cert event is appended to audit_events after bundle creation")
    void testCertificateEventAppendedAfterBundle() throws Exception {
        String now = Instant.now().toString();
        String later = Instant.now().plus(1, ChronoUnit.DAYS).toString();

        createAccessEvent("CLIENT_ACCOUNT_DATA_VIEWED", "user-1", "acc-1");

        long certsBefore = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_events WHERE event_type = 'COMPLIANCE_REPORT_GENERATED'", Long.class);

        performBundle(bundleRequest("acc-1", null, now, later, "APPROVAL-CERT-001", "REG_AUDIT"), 201);

        long certsAfter = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_events WHERE event_type = 'COMPLIANCE_REPORT_GENERATED'", Long.class);

        assertEquals(certsBefore + 1, certsAfter, "Exactly one cert event must be appended");

        // Cert payload must contain expected safe metadata — no sensitive PII
        List<Map<String, Object>> certRows = jdbcTemplate.queryForList(
            "SELECT payload_json FROM audit_events WHERE event_type = 'COMPLIANCE_REPORT_GENERATED' ORDER BY chain_position DESC LIMIT 1"
        );
        String certPayloadJson = (String) certRows.get(0).get("payload_json");
        JsonNode certPayload = objectMapper.readTree(certPayloadJson);

        assertEquals("APPROVAL-CERT-001", certPayload.get("approvalRef").asText());
        assertEquals("REG_AUDIT", certPayload.get("reasonCode").asText());
        assertTrue(certPayload.has("bundleDigest"));
        assertTrue(certPayload.has("criteriaDigest"));
        assertTrue(certPayload.has("recordCount"));
        assertTrue(certPayload.has("generatedAt"));
        // Must NOT contain raw account data or private keys
        assertFalse(certPayload.has("accountId"));
        assertFalse(certPayload.has("privateKey"));
    }

    @Test
    @DisplayName("Archived events excluded by default; included when includeArchived=true")
    void testArchivedEventBehavior() throws Exception {
        String now = Instant.now().toString();
        String later = Instant.now().plus(1, ChronoUnit.DAYS).toString();

        String eventId = createAccessEvent("CLIENT_ACCOUNT_DATA_VIEWED", "user-1", "acc-1");
        jdbcTemplate.update("UPDATE audit_events SET is_archived = TRUE WHERE id = ?", eventId);

        // Default: archived excluded
        ObjectNode reqDefault = bundleRequest("acc-1", null, now, later, "APPROVAL-ARCH-A", null);
        MvcResult defaultResult = performBundle(reqDefault, 201);
        assertEquals(0, objectMapper.readTree(defaultResult.getResponse().getContentAsString()).get("recordCount").asInt());

        // With includeArchived=true
        ObjectNode reqInclude = bundleRequest("acc-1", null, now, later, "APPROVAL-ARCH-B", null);
        reqInclude.put("includeArchived", true);
        MvcResult includeResult = performBundle(reqInclude, 201);
        assertEquals(1, objectMapper.readTree(includeResult.getResponse().getContentAsString()).get("recordCount").asInt());
    }

    @Test
    @DisplayName("Masked events are noted in bundle but do not break signature verification")
    void testMaskingDoesNotBreakSignatureVerification() throws Exception {
        String now = Instant.now().toString();
        String later = Instant.now().plus(1, ChronoUnit.DAYS).toString();

        // Create an event with a sensitive payload field that triggers masking
        createAccessEventWithPayload("CLIENT_ACCOUNT_DATA_VIEWED", "user-1", "acc-1",
            objectMapper.createObjectNode().put("accountNumber", "1234-5678-9012"));

        ObjectNode req = bundleRequest("acc-1", null, now, later, "APPROVAL-MASK-001", null);
        MvcResult result = performBundle(req, 201);
        String bundleJson = result.getResponse().getContentAsString();

        // Signature verification should still pass (redacted flag noted in bundle)
        ExportVerifier.VerificationResult verification = exportVerifier.verify(bundleJson);
        assertTrue(verification.valid(), "Masked bundle must still verify: " + verification.errors());
    }

    // -----------------------------------------------------------------------
    // Validation — 400 paths
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Missing approvalRef returns HTTP 400")
    void testMissingApprovalRefReturns400() throws Exception {
        String now = Instant.now().toString();
        String later = Instant.now().plus(1, ChronoUnit.DAYS).toString();

        ObjectNode req = bundleRequest("acc-1", null, now, later, null, null);
        performBundle(req, 400);
    }

    @Test
    @DisplayName("Both accountId and resourceId returns HTTP 400")
    void testBothSelectorsReturns400() throws Exception {
        String now = Instant.now().toString();
        String later = Instant.now().plus(1, ChronoUnit.DAYS).toString();

        ObjectNode req = bundleRequest("acc-1", "res-1", now, later, "APPROVAL-001", null);
        performBundle(req, 400);
    }

    @Test
    @DisplayName("Neither accountId nor resourceId returns HTTP 400")
    void testNeitherSelectorReturns400() throws Exception {
        String now = Instant.now().toString();
        String later = Instant.now().plus(1, ChronoUnit.DAYS).toString();

        ObjectNode req = bundleRequest(null, null, now, later, "APPROVAL-001", null);
        performBundle(req, 400);
    }

    @Test
    @DisplayName("Excessive UTC window returns HTTP 400")
    void testExcessiveTimestampRangeReturns400() throws Exception {
        String from = Instant.now().toString();
        String to   = Instant.now().plus(200, ChronoUnit.DAYS).toString(); // exceeds 90-day max

        ObjectNode req = bundleRequest("acc-1", null, from, to, "APPROVAL-001", null);
        performBundle(req, 400);
    }

    @Test
    @DisplayName("from >= to returns HTTP 400")
    void testInvalidTimestampOrderReturns400() throws Exception {
        String now = Instant.now().toString();

        ObjectNode req = bundleRequest("acc-1", null, now, now, "APPROVAL-001", null);
        performBundle(req, 400);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String createAccessEvent(String eventType, String actorId, String accountId) throws Exception {
        return createAccessEventWithPayload(eventType, actorId, accountId, objectMapper.createObjectNode());
    }

    private String createAccessEventWithPayload(
        String eventType, String actorId, String accountId, ObjectNode payload
    ) throws Exception {
        ObjectNode req = objectMapper.createObjectNode();
        req.put("eventType", eventType);
        req.put("actorId", actorId);
        req.put("resourceType", "CLIENT_ACCOUNT");
        req.put("resourceId", accountId);
        req.set("payload", payload);

        MvcResult result = mockMvc.perform(
            post("/audit/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))
        ).andExpect(status().isCreated()).andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private ObjectNode bundleRequest(
        String accountId, String resourceId,
        String from, String to,
        String approvalRef, String reasonCode
    ) {
        ObjectNode req = objectMapper.createObjectNode();
        if (accountId  != null) req.put("accountId",  accountId);
        if (resourceId != null) req.put("resourceId", resourceId);
        if (from       != null) req.put("from", from);
        if (to         != null) req.put("to",   to);
        if (approvalRef!= null) req.put("approvalRef", approvalRef);
        if (reasonCode != null) req.put("reasonCode",  reasonCode);
        return req;
    }

    private MvcResult performBundle(ObjectNode requestBody, int expectedStatus) throws Exception {
        return mockMvc.perform(
            post("/audit/compliance/access-report/bundle")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestBody))
        ).andExpect(status().is(expectedStatus)).andReturn();
    }
}
