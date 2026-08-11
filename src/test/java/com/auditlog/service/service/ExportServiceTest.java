package com.auditlog.service.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ExportService - Unit Tests")
class ExportServiceTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final CanonicalHashService canonicalHashService = new CanonicalHashService();

    @Test
    @DisplayName("recordsDigest is deterministic for identical records")
    void testRecordsDigestDeterministic() {
        ArrayNode records1 = buildSampleRecords();
        ArrayNode records2 = buildSampleRecords();

        String digest1 = canonicalHashService.sha256Hex(canonicalHashService.canonicalizeValue(records1));
        String digest2 = canonicalHashService.sha256Hex(canonicalHashService.canonicalizeValue(records2));

        assertEquals(digest1, digest2, "Identical records must produce identical digest");
    }

    @Test
    @DisplayName("bundleDigest computation excludes the bundleDigest field itself")
    void testBundleDigestExcludesBundleDigestField() {
        ObjectNode bundle = objectMapper.createObjectNode();
        bundle.put("manifestVersion", "1");
        bundle.put("generatedAt", "2024-01-01T00:00:00Z");
        bundle.put("recordCount", 0);
        bundle.put("bundleDigest", "placeholder-should-be-excluded");

        ObjectNode bundleForDigest = bundle.deepCopy();
        bundleForDigest.remove("bundleDigest");

        String canonical = canonicalHashService.canonicalizeValue(bundleForDigest);
        assertFalse(canonical.contains("bundleDigest"), "bundleDigest field must not appear in canonical form used for digest");
    }

    @Test
    @DisplayName("bundleDigest computation excludes future signature fields")
    void testBundleDigestExcludesFutureSignatureFields() {
        ObjectNode bundle = objectMapper.createObjectNode();
        bundle.put("manifestVersion", "1");
        bundle.put("recordsDigest", "records-digest");
        bundle.put("bundleDigest", "bundle-digest");
        bundle.put("signature", "future-signature");
        bundle.put("signatureAlgorithm", "Ed25519");
        bundle.put("signingKeyId", "kid-1");

        ObjectNode bundleForDigest = ExportDigestSupport.unsignedBundle(bundle);
        String canonical = canonicalHashService.canonicalizeValue(bundleForDigest);

        assertFalse(canonical.contains("bundleDigest"));
        assertFalse(canonical.contains("signature"));
        assertFalse(canonical.contains("signatureAlgorithm"));
        assertFalse(canonical.contains("signingKeyId"));
        assertTrue(canonical.contains("recordsDigest"));
    }

    @Test
    @DisplayName("recordsDigest changes when a record value changes")
    void testRecordsDigestChangesWhenRecordChanges() {
        ArrayNode records1 = objectMapper.createArrayNode();
        ObjectNode record1 = objectMapper.createObjectNode();
        record1.put("id", "event-1");
        record1.put("contentHash", "abc123");
        records1.add(record1);

        ArrayNode records2 = objectMapper.createArrayNode();
        ObjectNode record2 = objectMapper.createObjectNode();
        record2.put("id", "event-1");
        record2.put("contentHash", "xyz789");  // different value
        records2.add(record2);

        String digest1 = canonicalHashService.sha256Hex(canonicalHashService.canonicalizeValue(records1));
        String digest2 = canonicalHashService.sha256Hex(canonicalHashService.canonicalizeValue(records2));

        assertNotEquals(digest1, digest2, "Different records must produce different digest");
    }

    @Test
    @DisplayName("Empty records array digest is stable and matches sha256 of '[]'")
    void testEmptyExportDigestIsStable() {
        ArrayNode empty1 = objectMapper.createArrayNode();
        ArrayNode empty2 = objectMapper.createArrayNode();

        String digest1 = canonicalHashService.sha256Hex(canonicalHashService.canonicalizeValue(empty1));
        String digest2 = canonicalHashService.sha256Hex(canonicalHashService.canonicalizeValue(empty2));

        assertEquals(digest1, digest2, "Empty array must produce stable digest");

        // Canonical form of empty array is "[]"
        String expectedDigest = canonicalHashService.sha256Hex("[]");
        assertEquals(expectedDigest, digest1, "Empty array canonical form must be '[]'");
    }

    private ArrayNode buildSampleRecords() {
        ArrayNode records = objectMapper.createArrayNode();
        ObjectNode record = objectMapper.createObjectNode();
        record.put("id", "event-abc");
        record.put("chainPosition", 1L);
        record.put("eventType", "USER_LOGIN");
        record.put("actorId", "user-1");
        record.put("resourceType", "ACCOUNT");
        record.put("resourceId", "acc-1");
        record.set("payload", objectMapper.createObjectNode().put("action", "login"));
        record.put("timestamp", "2024-01-01T00:00:00Z");
        record.put("ingestedAt", "2024-01-01T00:00:01Z");
        record.put("contentHash", "abc123");
        record.put("previousHash", "0000000000000000000000000000000000000000000000000000000000000000");
        record.put("chainHash", "def456");
        record.put("hashVersion", "v1");
        record.put("redacted", false);
        record.set("redactedPointers", objectMapper.createArrayNode());
        records.add(record);
        return records;
    }
}
