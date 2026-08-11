package com.auditlog.service.service;

import com.auditlog.service.config.EncryptionProperties;
import com.auditlog.service.domain.AuditEvent;
import com.auditlog.service.domain.AuditEventEncryptionKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PayloadEncryptionService - Field Encryption")
class PayloadEncryptionServiceTest {
    private static final String TEST_MASTER_KEY_BASE64 = "MDEyMzQ1Njc4OUFCQ0RFRjAxMjM0NTY3ODlBQkNERUY=";
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final CanonicalHashService canonicalHashService = new CanonicalHashService();
    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("Encrypts and decrypts JSON values while preserving original types")
    void testEncryptDecryptRoundTripPreservesTypes() {
        EncryptionProperties properties = encryptionProperties("/text", "/number", "/flag", "/details", "/tags");
        PayloadEncryptionService service = new PayloadEncryptionService(
            properties,
            new LocalEncryptionKeyProvider(properties),
            canonicalHashService,
            fixedClock
        );

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("text", "sensitive");
        payload.put("number", 42);
        payload.put("flag", true);
        payload.putObject("details").put("nested", "value");
        payload.putArray("tags").add("alpha").add("beta");

        PayloadEncryptionService.EncryptionWriteResult encrypted = service.encryptPayloadForStorage("event-1", payload);
        AuditEvent event = buildEvent("event-1", encrypted.payload());

        PayloadEncryptionService.PayloadViewResult decrypted = service.renderPayloadForResponse(
            event,
            encrypted.keyRecords(),
            "[REDACTED]"
        );

        assertEquals("sensitive", decrypted.payload().get("text").asText());
        assertEquals(42, decrypted.payload().get("number").asInt());
        assertTrue(decrypted.payload().get("flag").asBoolean());
        assertEquals("value", decrypted.payload().get("details").get("nested").asText());
        assertEquals("alpha", decrypted.payload().get("tags").get(0).asText());
        assertTrue(decrypted.destroyedPointers().isEmpty());
    }

    @Test
    @DisplayName("Same plaintext produces different ciphertext because of random nonces")
    void testSamePlaintextProducesDifferentCiphertext() {
        EncryptionProperties properties = encryptionProperties("/accountNumber");
        PayloadEncryptionService service = new PayloadEncryptionService(
            properties,
            new LocalEncryptionKeyProvider(properties),
            canonicalHashService,
            fixedClock
        );

        JsonNode first = service.encryptPayloadForStorage("event-1", payloadWith("accountNumber", "123456789")).payload();
        JsonNode second = service.encryptPayloadForStorage("event-2", payloadWith("accountNumber", "123456789")).payload();

        JsonNode firstEnvelope = first.get("accountNumber").get("$encrypted");
        JsonNode secondEnvelope = second.get("accountNumber").get("$encrypted");

        assertNotEquals(firstEnvelope.get("nonce").asText(), secondEnvelope.get("nonce").asText());
        assertNotEquals(firstEnvelope.get("ciphertext").asText(), secondEnvelope.get("ciphertext").asText());
    }

    @Test
    @DisplayName("Ciphertext tampering fails authenticated decryption")
    void testTamperedCiphertextFailsAuthentication() {
        EncryptionProperties properties = encryptionProperties("/accountNumber");
        PayloadEncryptionService service = new PayloadEncryptionService(
            properties,
            new LocalEncryptionKeyProvider(properties),
            canonicalHashService,
            fixedClock
        );

        PayloadEncryptionService.EncryptionWriteResult encrypted = service.encryptPayloadForStorage("event-1", payloadWith("accountNumber", "123456789"));
        ObjectNode tamperedPayload = (ObjectNode) encrypted.payload().deepCopy();
        ObjectNode encryptedNode = (ObjectNode) tamperedPayload.get("accountNumber").get("$encrypted");
        encryptedNode.put("ciphertext", mutateBase64(encryptedNode.get("ciphertext").asText()));

        assertThrows(
            PayloadDecryptionException.class,
            () -> service.renderPayloadForResponse(buildEvent("event-1", tamperedPayload), encrypted.keyRecords(), "[REDACTED]")
        );
    }

    @Test
    @DisplayName("Wrong master key fails decryption")
    void testWrongKeyFailsDecryption() {
        EncryptionProperties encryptingProperties = encryptionProperties("/accountNumber");
        PayloadEncryptionService encryptingService = new PayloadEncryptionService(
            encryptingProperties,
            new LocalEncryptionKeyProvider(encryptingProperties),
            canonicalHashService,
            fixedClock
        );
        PayloadEncryptionService.EncryptionWriteResult encrypted = encryptingService.encryptPayloadForStorage("event-1", payloadWith("accountNumber", "123456789"));

        EncryptionProperties decryptingProperties = encryptionProperties("/accountNumber");
        decryptingProperties.setMasterKeyBase64(Base64.getEncoder().encodeToString("different-master-key-32-bytes!!??".getBytes()));
        PayloadEncryptionService decryptingService = new PayloadEncryptionService(
            decryptingProperties,
            new LocalEncryptionKeyProvider(decryptingProperties),
            canonicalHashService,
            fixedClock
        );

        assertThrows(
            PayloadDecryptionException.class,
            () -> decryptingService.renderPayloadForResponse(buildEvent("event-1", encrypted.payload()), encrypted.keyRecords(), "[REDACTED]")
        );
    }

    @Test
    @DisplayName("AAD binds the ciphertext to the event identifier")
    void testEventIdBoundThroughAad() {
        EncryptionProperties properties = encryptionProperties("/accountNumber");
        PayloadEncryptionService service = new PayloadEncryptionService(
            properties,
            new LocalEncryptionKeyProvider(properties),
            canonicalHashService,
            fixedClock
        );

        PayloadEncryptionService.EncryptionWriteResult encrypted = service.encryptPayloadForStorage("event-1", payloadWith("accountNumber", "123456789"));

        assertThrows(
            PayloadDecryptionException.class,
            () -> service.renderPayloadForResponse(buildEvent("event-2", encrypted.payload()), encrypted.keyRecords(), "[REDACTED]")
        );
    }

    @Test
    @DisplayName("Rejects unsupported envelope versions during decryption")
    void testUnsupportedEnvelopeVersionIsRejected() {
        EncryptionProperties properties = encryptionProperties("/accountNumber");
        PayloadEncryptionService service = new PayloadEncryptionService(
            properties,
            new LocalEncryptionKeyProvider(properties),
            canonicalHashService,
            fixedClock
        );

        PayloadEncryptionService.EncryptionWriteResult encrypted = service.encryptPayloadForStorage("event-1", payloadWith("accountNumber", "123456789"));
        ObjectNode tamperedPayload = (ObjectNode) encrypted.payload().deepCopy();
        ObjectNode encryptedNode = (ObjectNode) tamperedPayload.get("accountNumber").get("$encrypted");
        encryptedNode.put("version", "enc-v2");

        assertThrows(
            PayloadDecryptionException.class,
            () -> service.renderPayloadForResponse(buildEvent("event-1", tamperedPayload), encrypted.keyRecords(), "[REDACTED]")
        );
    }

    @Test
    @DisplayName("Absent configured pointers are ignored while present ones are encrypted")
    void testAbsentPointersAreIgnored() {
        EncryptionProperties properties = encryptionProperties("/accountNumber", "/personalIdentifier");
        PayloadEncryptionService service = new PayloadEncryptionService(
            properties,
            new LocalEncryptionKeyProvider(properties),
            canonicalHashService,
            fixedClock
        );

        PayloadEncryptionService.EncryptionWriteResult encrypted = service.encryptPayloadForStorage("event-1", payloadWith("accountNumber", "123456789"));

        assertEquals(1, encrypted.keyRecords().size());
        assertTrue(encrypted.payload().has("accountNumber"));
        assertFalse(encrypted.payload().has("personalIdentifier"));
    }

    @Test
    @DisplayName("Encrypted stored payload does not contain plaintext values")
    void testNoPlaintextAppearsInStoredRepresentation() {
        EncryptionProperties properties = encryptionProperties("/accountNumber", "/personalIdentifier");
        PayloadEncryptionService service = new PayloadEncryptionService(
            properties,
            new LocalEncryptionKeyProvider(properties),
            canonicalHashService,
            fixedClock
        );

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("accountNumber", "123456789");
        payload.put("personalIdentifier", "PI-001");

        PayloadEncryptionService.EncryptionWriteResult encrypted = service.encryptPayloadForStorage("event-1", payload);
        String persistedPayload = encrypted.payload().toString();

        assertFalse(persistedPayload.contains("123456789"));
        assertFalse(persistedPayload.contains("PI-001"));
        assertTrue(persistedPayload.contains("$encrypted"));
    }

    private EncryptionProperties encryptionProperties(String... pointers) {
        EncryptionProperties properties = new EncryptionProperties();
        properties.setEnabled(true);
        properties.setMasterKeyBase64(TEST_MASTER_KEY_BASE64);
        properties.setSensitivePointers(List.of(pointers));
        properties.validate();
        return properties;
    }

    private ObjectNode payloadWith(String field, String value) {
        return objectMapper.createObjectNode().put(field, value);
    }

    private AuditEvent buildEvent(String eventId, JsonNode payload) {
        return new AuditEvent(
            eventId,
            1,
            "TEST_EVENT",
            "actor-1",
            "ACCOUNT",
            "account-1",
            payload,
            "2026-08-11T00:00:00Z",
            "2026-08-11T00:00:00Z",
            "content-hash",
            "previous-hash",
            "chain-hash",
            "v1"
        );
    }

    private String mutateBase64(String value) {
        char replacement = value.charAt(value.length() - 1) == 'A' ? 'B' : 'A';
        return value.substring(0, value.length() - 1) + replacement;
    }
}
