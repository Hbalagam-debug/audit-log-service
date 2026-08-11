package com.auditlog.service.integration;

import com.auditlog.service.SpringBootTestSupport;
import com.auditlog.service.repository.AuditEventRepository;
import com.auditlog.service.service.CanonicalHashService;
import com.auditlog.service.service.ChainVerificationService;
import com.auditlog.service.domain.AuditEvent;
import com.auditlog.service.domain.ChainVerificationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("TamperingDetectionTest - Direct Database Tampering Detection")
class TamperingDetectionTest extends SpringBootTestSupport {
    @Autowired
    private ChainVerificationService verificationService;

    @Autowired
    private AuditEventRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CanonicalHashService hashService;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private static final String TEST_TIMESTAMP = "2026-08-10T20:00:00Z";

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM audit_events");
    }

    @Test
    @DisplayName("Detects content hash tampering")
    void testDetectContentHashTampering() {
        insertValidEvent("id-1", 1, "EVENT1", "user1", "ACCOUNT", "acc1", hashService.getGenesisHash());
        assertTrue(verificationService.verify().isIntact());
        
        // Tamper with content_hash
        jdbcTemplate.update(
            "UPDATE audit_events SET content_hash = ? WHERE id = ?",
            "tampered_hash_0000000000000000000000000000000000000000000000000000000000",
            "id-1"
        );

        ChainVerificationResult result = verificationService.verify();

        assertFalse(result.isIntact());
        assertEquals("CONTENT_HASH_MISMATCH", result.getViolationType().toString());
    }

    @Test
    @DisplayName("Detects previous hash tampering")
    void testDetectPreviousHashTampering() {
        AuditEvent first = insertValidEvent("id-1", 1, "EVENT1", "user1", "ACCOUNT", "acc1", hashService.getGenesisHash());
        insertValidEvent("id-2", 2, "EVENT2", "user2", "ACCOUNT", "acc2", first.getChainHash());
        assertTrue(verificationService.verify().isIntact());

        // Tamper with previous_hash of second record
        jdbcTemplate.update(
            "UPDATE audit_events SET previous_hash = ? WHERE id = ?",
            "tampered_prev_hash_0000000000000000000000000000000000000000000000000000",
            "id-2"
        );

        ChainVerificationResult result = verificationService.verify();

        assertFalse(result.isIntact());
        assertEquals("PREVIOUS_HASH_MISMATCH", result.getViolationType().toString());
    }

    @Test
    @DisplayName("Detects chain hash tampering")
    void testDetectChainHashTampering() {
        insertValidEvent("id-1", 1, "EVENT1", "user1", "ACCOUNT", "acc1", hashService.getGenesisHash());
        assertTrue(verificationService.verify().isIntact());

        // Tamper with chain_hash
        jdbcTemplate.update(
            "UPDATE audit_events SET chain_hash = ? WHERE id = ?",
            "tampered_chain_hash_000000000000000000000000000000000000000000000000",
            "id-1"
        );

        ChainVerificationResult result = verificationService.verify();

        assertFalse(result.isIntact());
        assertEquals("CHAIN_HASH_MISMATCH", result.getViolationType().toString());
    }

    @Test
    @DisplayName("Detects position gaps")
    void testDetectPositionGap() {
        AuditEvent first = insertValidEvent("id-1", 1, "EVENT1", "user1", "ACCOUNT", "acc1", hashService.getGenesisHash());
        AuditEvent second = insertValidEvent("id-2", 2, "EVENT2", "user2", "ACCOUNT", "acc2", first.getChainHash());
        insertValidEvent("id-3", 3, "EVENT3", "user3", "ACCOUNT", "acc3", second.getChainHash());
        assertTrue(verificationService.verify().isIntact());

        jdbcTemplate.update("DELETE FROM audit_events WHERE id = ?", "id-2");

        ChainVerificationResult result = verificationService.verify();

        assertFalse(result.isIntact());
        assertEquals("POSITION_GAP", result.getViolationType().toString());
    }

    private AuditEvent insertValidEvent(
        String id,
        long chainPosition,
        String eventType,
        String actorId,
        String resourceType,
        String resourceId,
        String previousHash
    ) {
        JsonNode payload = assertDoesNotThrow(() -> objectMapper.readTree("{\"test\":true}"));
        String contentHash = hashService.computeContentHash(
            eventType,
            actorId,
            resourceType,
            resourceId,
            payload,
            TEST_TIMESTAMP
        );
        String chainHash = hashService.computeChainHash(chainPosition, previousHash, contentHash);

        AuditEvent event = new AuditEvent(
            id,
            chainPosition,
            eventType,
            actorId,
            resourceType,
            resourceId,
            payload,
            TEST_TIMESTAMP,
            TEST_TIMESTAMP,
            contentHash,
            previousHash,
            chainHash,
            hashService.getHashVersion()
        );

        repository.insert(event);
        return event;
    }
}
