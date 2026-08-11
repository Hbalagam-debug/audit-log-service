package com.auditlog.service.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CanonicalHashService - Hash Determinism and Algorithm Tests")
class CanonicalHashServiceTest {
    private final CanonicalHashService hashService = new CanonicalHashService();
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    @DisplayName("Canonical hash is deterministic - same input produces same hash")
    void testContentHashDeterminism() {
        JsonNode payload = assertDoesNotThrow(() ->
            objectMapper.readTree("{\"key\": \"value\", \"nested\": {\"field\": 123}}")
        );

        String hash1 = hashService.computeContentHash(
            "USER_LOGIN",
            "user-123",
            "ACCOUNT",
            "account-456",
            payload,
            "2026-08-10T20:00:00Z"
        );

        String hash2 = hashService.computeContentHash(
            "USER_LOGIN",
            "user-123",
            "ACCOUNT",
            "account-456",
            payload,
            "2026-08-10T20:00:00Z"
        );

        assertEquals(hash1, hash2, "Same input should produce identical hash");
        assertTrue(hash1.matches("^[a-f0-9]{64}$"), "Hash should be 64 lowercase hex chars");
    }

    @Test
    @DisplayName("Different JSON key order produces same hash - keys sorted alphabetically")
    void testJsonKeyOrderDoesNotAffectHash() throws Exception {
        String json1 = "{\"a\": 1, \"b\": 2, \"c\": 3}";
        String json2 = "{\"c\": 3, \"a\": 1, \"b\": 2}";
        
        JsonNode payload1 = objectMapper.readTree(json1);
        JsonNode payload2 = objectMapper.readTree(json2);

        String hash1 = hashService.computeContentHash(
            "TEST",
            "actor",
            "RESOURCE",
            "id",
            payload1,
            "2026-08-10T20:00:00Z"
        );

        String hash2 = hashService.computeContentHash(
            "TEST",
            "actor",
            "RESOURCE",
            "id",
            payload2,
            "2026-08-10T20:00:00Z"
        );

        assertEquals(hash1, hash2, "Different key order should produce same hash");
    }

    @Test
    @DisplayName("Array element order change produces different hash")
    void testArrayElementOrderChangesHash() throws Exception {
        String payload1Str = "{\"items\": [1, 2, 3]}";
        String payload2Str = "{\"items\": [3, 2, 1]}";
        
        JsonNode payload1 = objectMapper.readTree(payload1Str);
        JsonNode payload2 = objectMapper.readTree(payload2Str);

        String hash1 = hashService.computeContentHash(
            "TEST",
            "actor",
            "RESOURCE",
            "id",
            payload1,
            "2026-08-10T20:00:00Z"
        );

        String hash2 = hashService.computeContentHash(
            "TEST",
            "actor",
            "RESOURCE",
            "id",
            payload2,
            "2026-08-10T20:00:00Z"
        );

        assertNotEquals(hash1, hash2, "Array element reordering should produce different hash");
    }

    @Test
    @DisplayName("Single character change in content produces different hash")
    void testSingleCharacterChangeAltersHash() throws Exception {
        JsonNode payload = objectMapper.readTree("{\"data\": \"value\"}");

        String hash1 = hashService.computeContentHash(
            "TEST",
            "actor-A",
            "RESOURCE",
            "id",
            payload,
            "2026-08-10T20:00:00Z"
        );

        String hash2 = hashService.computeContentHash(
            "TEST",
            "actor-B",
            "RESOURCE",
            "id",
            payload,
            "2026-08-10T20:00:00Z"
        );

        assertNotEquals(hash1, hash2, "Even single character change should alter hash");
    }

    @Test
    @DisplayName("Chain hash includes position and previous hash")
    void testChainHashIncludesPositionAndPrevious() {
        String prevHash = "abc123def456";
        String contentHash = "xyz789uvw012";

        String chainHash1 = hashService.computeChainHash(1L, prevHash, contentHash);
        String chainHash2 = hashService.computeChainHash(2L, prevHash, contentHash);

        assertNotEquals(chainHash1, chainHash2, "Different positions should produce different chain hashes");
    }

    @Test
    @DisplayName("Genesis hash is 64 lowercase zeros")
    void testGenesisHashValue() {
        String genesisHash = hashService.getGenesisHash();
        
        assertEquals(64, genesisHash.length(), "Genesis hash should be 64 characters");
        assertEquals("0000000000000000000000000000000000000000000000000000000000000000", genesisHash);
    }

    @Test
    @DisplayName("Hash version is v1")
    void testHashVersion() {
        assertEquals("v1", hashService.getHashVersion());
    }
}
