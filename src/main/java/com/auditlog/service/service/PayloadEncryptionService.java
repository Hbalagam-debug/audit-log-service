package com.auditlog.service.service;

import com.auditlog.service.config.EncryptionProperties;
import com.auditlog.service.domain.AuditEvent;
import com.auditlog.service.domain.AuditEventEncryptionKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PayloadEncryptionService {
    private static final Logger logger = LoggerFactory.getLogger(PayloadEncryptionService.class);
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int NONCE_LENGTH_BYTES = 12;

    private final EncryptionProperties encryptionProperties;
    private final EncryptionKeyProvider encryptionKeyProvider;
    private final CanonicalHashService canonicalHashService;
    private final Clock clock;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final SecureRandom secureRandom = new SecureRandom();
    private final boolean enabled;

    public static PayloadEncryptionService disabled() {
        return new PayloadEncryptionService();
    }

    private PayloadEncryptionService() {
        this.encryptionProperties = null;
        this.encryptionKeyProvider = null;
        this.canonicalHashService = null;
        this.clock = Clock.systemUTC();
        this.enabled = false;
    }

    @Autowired
    public PayloadEncryptionService(
        EncryptionProperties encryptionProperties,
        EncryptionKeyProvider encryptionKeyProvider,
        CanonicalHashService canonicalHashService
    ) {
        this(encryptionProperties, encryptionKeyProvider, canonicalHashService, Clock.systemUTC());
    }

    public PayloadEncryptionService(
        EncryptionProperties encryptionProperties,
        EncryptionKeyProvider encryptionKeyProvider,
        CanonicalHashService canonicalHashService,
        Clock clock
    ) {
        this.encryptionProperties = encryptionProperties;
        this.encryptionKeyProvider = encryptionKeyProvider;
        this.canonicalHashService = canonicalHashService;
        this.clock = clock;
        this.enabled = encryptionProperties != null && encryptionProperties.isEnabled();
    }

    public EncryptionWriteResult encryptPayloadForStorage(String eventId, JsonNode payload) {
        JsonNode storedPayload = payload.deepCopy();
        if (!enabled) {
            return new EncryptionWriteResult(storedPayload, List.of());
        }

        List<AuditEventEncryptionKey> keyRecords = new ArrayList<>();
        String createdAt = nowUtc();
        for (String pointer : encryptionProperties.getNormalizedSensitivePointers()) {
            JsonNode currentValue = JsonPointerUtils.getNode(storedPayload, pointer);
            if (currentValue == null) {
                continue;
            }

            String keyRef = "dek-" + UUID.randomUUID();
            EncryptionKeyProvider.GeneratedKeyMaterial generated = encryptionKeyProvider.generateDataKey(keyRef, encryptionProperties.getFormatVersion());
            try {
                String canonicalValue = canonicalHashService.canonicalizeValue(currentValue);
                EncryptedEnvelope envelope = encryptValue(
                    eventId,
                    pointer,
                    keyRef,
                    canonicalValue,
                    generated.plaintextDek()
                );
                JsonPointerUtils.setValue(storedPayload, pointer, envelope.toJson(objectMapper));
                keyRecords.add(new AuditEventEncryptionKey(
                    keyRef,
                    eventId,
                    List.of(pointer),
                    generated.wrapAlgorithm(),
                    generated.wrappedDek(),
                    generated.wrapNonce(),
                    "ACTIVE",
                    createdAt,
                    null,
                    null,
                    null,
                    null,
                    null,
                    encryptionProperties.getFormatVersion()
                ));
            } finally {
                Arrays.fill(generated.plaintextDek(), (byte) 0);
            }
        }

        return new EncryptionWriteResult(storedPayload, keyRecords);
    }

    public PayloadViewResult renderPayloadForResponse(
        AuditEvent event,
        List<AuditEventEncryptionKey> eventKeys,
        String maskValue
    ) {
        JsonNode payload = event.getPayload().deepCopy();
        if (!enabled) {
            return new PayloadViewResult(payload, List.of());
        }

        Map<String, AuditEventEncryptionKey> keysByPointer = new LinkedHashMap<>();
        for (AuditEventEncryptionKey keyRecord : eventKeys) {
            for (String pointer : keyRecord.getEncryptedPointers()) {
                keysByPointer.put(pointer, keyRecord);
            }
        }

        List<String> destroyedPointers = new ArrayList<>();
        List<String> encryptedPointers = new ArrayList<>(keysByPointer.keySet());
        encryptedPointers.addAll(encryptionProperties.getNormalizedSensitivePointers());
        for (String pointer : encryptedPointers.stream().distinct().sorted().toList()) {
            JsonNode node = JsonPointerUtils.getNode(payload, pointer);
            if (node == null) {
                continue;
            }
            if (!isEncryptedEnvelope(node)) {
                continue;
            }

            AuditEventEncryptionKey keyRecord = keysByPointer.get(pointer);
            if (keyRecord == null) {
                logDecryptionFailure(event.getId(), null, "MISSING_KEY_METADATA");
                throw new PayloadDecryptionException();
            }

            EncryptedEnvelope envelope = EncryptedEnvelope.fromJson(node);
            if (!keyRecord.getKeyRef().equals(envelope.keyRef())) {
                logDecryptionFailure(event.getId(), keyRecord.getKeyRef(), "KEY_REF_MISMATCH");
                throw new PayloadDecryptionException();
            }
            if (keyRecord.isDestroyed()) {
                JsonPointerUtils.setValue(payload, pointer, objectMapper.getNodeFactory().textNode(maskValue));
                destroyedPointers.add(pointer);
                continue;
            }

            try {
                byte[] plaintextDek = encryptionKeyProvider.unwrapActiveKey(keyRecord);
                try {
                    JsonNode decrypted = decryptValue(event.getId(), pointer, envelope, plaintextDek);
                    JsonPointerUtils.setValue(payload, pointer, decrypted);
                } finally {
                    Arrays.fill(plaintextDek, (byte) 0);
                }
            } catch (PayloadDecryptionException ex) {
                throw ex;
            } catch (Exception ex) {
                logDecryptionFailure(event.getId(), keyRecord.getKeyRef(), "DECRYPTION_FAILED");
                throw new PayloadDecryptionException();
            }
        }

        return new PayloadViewResult(payload, destroyedPointers.stream().distinct().sorted().toList());
    }

    public boolean isEncryptedEnvelope(JsonNode node) {
        return node != null
            && node.isObject()
            && node.size() == 1
            && node.has("$encrypted")
            && node.get("$encrypted").isObject();
    }

    public boolean managesPointer(String pointer) {
        return enabled && encryptionProperties.managesPointer(pointer);
    }

    private EncryptedEnvelope encryptValue(
        String eventId,
        String pointer,
        String keyRef,
        String canonicalValue,
        byte[] plaintextDek
    ) {
        byte[] nonce = new byte[NONCE_LENGTH_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(plaintextDek, "AES"),
                new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce)
            );
            cipher.updateAAD(buildFieldAad(eventId, pointer));
            byte[] ciphertext = cipher.doFinal(canonicalValue.getBytes(StandardCharsets.UTF_8));
            return new EncryptedEnvelope(
                encryptionProperties.getFormatVersion(),
                encryptionProperties.getAlgorithm(),
                keyRef,
                Base64.getEncoder().encodeToString(nonce),
                Base64.getEncoder().encodeToString(ciphertext)
            );
        } catch (Exception ex) {
            throw new RuntimeException("Failed to encrypt payload field", ex);
        }
    }

    private JsonNode decryptValue(
        String eventId,
        String pointer,
        EncryptedEnvelope envelope,
        byte[] plaintextDek
    ) {
        try {
            if (!encryptionProperties.getFormatVersion().equals(envelope.version())
                || !encryptionProperties.getAlgorithm().equals(envelope.algorithm())) {
                logDecryptionFailure(eventId, envelope.keyRef(), "UNSUPPORTED_ENVELOPE");
                throw new PayloadDecryptionException();
            }

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                Cipher.DECRYPT_MODE,
                new SecretKeySpec(plaintextDek, "AES"),
                new GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.getDecoder().decode(envelope.nonce()))
            );
            cipher.updateAAD(buildFieldAad(eventId, pointer));
            byte[] plaintext = cipher.doFinal(Base64.getDecoder().decode(envelope.ciphertext()));
            try {
                return objectMapper.readTree(new String(plaintext, StandardCharsets.UTF_8));
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
        } catch (PayloadDecryptionException ex) {
            throw ex;
        } catch (Exception ex) {
            logDecryptionFailure(eventId, envelope.keyRef(), "AUTHENTICATION_FAILED");
            throw new PayloadDecryptionException();
        }
    }

    private byte[] buildFieldAad(String eventId, String pointer) {
        return (eventId + ":" + pointer + ":" + encryptionProperties.getFormatVersion())
            .getBytes(StandardCharsets.UTF_8);
    }

    private String nowUtc() {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME
            .format(Instant.now(clock).atOffset(ZoneOffset.UTC))
            .replace("+00:00", "Z");
    }

    private void logDecryptionFailure(String eventId, String keyRef, String category) {
        logger.warn("Encrypted payload unavailable: eventId={}, keyRef={}, category={}", eventId, keyRef, category);
    }

    public record EncryptionWriteResult(
        JsonNode payload,
        List<AuditEventEncryptionKey> keyRecords
    ) {
    }

    public record PayloadViewResult(
        JsonNode payload,
        List<String> destroyedPointers
    ) {
    }

    private record EncryptedEnvelope(
        String version,
        String algorithm,
        String keyRef,
        String nonce,
        String ciphertext
    ) {
        static EncryptedEnvelope fromJson(JsonNode node) {
            JsonNode encryptedNode = node.get("$encrypted");
            if (encryptedNode == null
                || !encryptedNode.isObject()
                || !encryptedNode.has("version")
                || !encryptedNode.has("algorithm")
                || !encryptedNode.has("keyRef")
                || !encryptedNode.has("nonce")
                || !encryptedNode.has("ciphertext")) {
                throw new PayloadDecryptionException();
            }
            return new EncryptedEnvelope(
                encryptedNode.get("version").asText(),
                encryptedNode.get("algorithm").asText(),
                encryptedNode.get("keyRef").asText(),
                encryptedNode.get("nonce").asText(),
                encryptedNode.get("ciphertext").asText()
            );
        }

        JsonNode toJson(ObjectMapper objectMapper) {
            ObjectNode root = objectMapper.createObjectNode();
            ObjectNode encrypted = root.putObject("$encrypted");
            encrypted.put("version", version);
            encrypted.put("algorithm", algorithm);
            encrypted.put("keyRef", keyRef);
            encrypted.put("nonce", nonce);
            encrypted.put("ciphertext", ciphertext);
            return root;
        }
    }
}
