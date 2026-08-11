package com.auditlog.service.service;

import com.auditlog.service.api.dto.AuditEventCreateRequest;
import com.auditlog.service.api.dto.RedactionRequest;
import com.auditlog.service.api.dto.RedactionResponse;
import com.auditlog.service.config.RedactionProperties;
import com.auditlog.service.domain.AuditEvent;
import com.auditlog.service.domain.AuditEventEncryptionKey;
import com.auditlog.service.domain.RedactionOverlay;
import com.auditlog.service.repository.AuditEventRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class RedactionService {
    private static final String REDACTION_EVENT_TYPE = "REDACTION_APPLIED";
    private static final String MASK_MODE = "MASK";
    private static final String CRYPTO_ERASURE_MODE = "CRYPTO_ERASURE";
    private static final String HYBRID_MODE = "HYBRID";
    private static final Set<String> RESERVED_POINTERS = Set.of(
        "/id",
        "/chainPosition",
        "/eventType",
        "/actorId",
        "/resourceType",
        "/resourceId",
        "/timestamp",
        "/ingestedAt",
        "/contentHash",
        "/previousHash",
        "/chainHash",
        "/hashVersion"
    );

    private final AuditEventRepository repository;
    private final AuditEventService auditEventService;
    private final RedactionProperties redactionProperties;
    private final PayloadEncryptionService payloadEncryptionService;
    private final Clock clock;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    public RedactionService(
        AuditEventRepository repository,
        AuditEventService auditEventService,
        RedactionProperties redactionProperties,
        Clock clock
    ) {
        this(repository, auditEventService, redactionProperties, PayloadEncryptionService.disabled(), clock);
    }

    @Autowired
    public RedactionService(
        AuditEventRepository repository,
        AuditEventService auditEventService,
        RedactionProperties redactionProperties,
        PayloadEncryptionService payloadEncryptionService,
        Clock clock
    ) {
        this.repository = repository;
        this.auditEventService = auditEventService;
        this.redactionProperties = redactionProperties;
        this.payloadEncryptionService = payloadEncryptionService;
        this.clock = clock;
    }

    @Transactional
    public RedactionResponse applyRedaction(String eventId, RedactionRequest request) {
        validateRequest(request);

        AuditEvent targetEvent = repository.findById(eventId)
            .orElseThrow(() -> new ResourceNotFoundException("Event not found: " + eventId));

        List<String> normalizedPointers = JsonPointerUtils.normalize(request.getJsonPointers());
        List<RedactionOverlay> existingActiveOverlays = repository.findActiveOverlaysForEventId(eventId);
        Set<String> existingOverlayPointers = existingActiveOverlays.stream()
            .map(RedactionOverlay::getJsonPointer)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        List<AuditEventEncryptionKey> encryptionKeys = repository.findEncryptionKeysForEventId(eventId);
        Map<String, AuditEventEncryptionKey> encryptionKeysByPointer = mapKeysByPointer(encryptionKeys);

        List<String> newLegacyPointers = new ArrayList<>();
        List<String> alreadyRedactedPointers = new ArrayList<>();
        List<AuditEventEncryptionKey> encryptionKeysToDestroy = new ArrayList<>();

        for (String pointer : normalizedPointers) {
            validateReservedPointer(pointer);

            JsonNode currentValue = JsonPointerUtils.getNode(targetEvent.getPayload(), pointer);
            if (currentValue == null) {
                throw new IllegalArgumentException("Pointer does not exist in payload: " + pointer);
            }

            if (payloadEncryptionService.isEncryptedEnvelope(currentValue)) {
                AuditEventEncryptionKey keyRecord = encryptionKeysByPointer.get(pointer);
                if (keyRecord == null) {
                    throw new IllegalArgumentException("Pointer is not managed by the current encryption policy: " + pointer);
                }
                validateRequestedPointerGroup(normalizedPointers, keyRecord);
                if (keyRecord.isDestroyed()) {
                    alreadyRedactedPointers.addAll(keyRecord.getEncryptedPointers());
                } else {
                    encryptionKeysToDestroy.add(keyRecord);
                }
                continue;
            }

            if (!isLegacyAllowed(pointer)) {
                throw new IllegalArgumentException("Pointer is not allowed by the current redaction policy: " + pointer);
            }
            if (existingOverlayPointers.contains(pointer)) {
                alreadyRedactedPointers.add(pointer);
            } else {
                newLegacyPointers.add(pointer);
            }
        }

        List<String> appliedPointers = new ArrayList<>(newLegacyPointers);
        for (AuditEventEncryptionKey keyRecord : encryptionKeysToDestroy) {
            appliedPointers.addAll(keyRecord.getEncryptedPointers());
        }
        appliedPointers = appliedPointers.stream().distinct().sorted().toList();
        alreadyRedactedPointers = alreadyRedactedPointers.stream().distinct().sorted().toList();

        String certificateEventId = null;
        if (!appliedPointers.isEmpty()) {
            String appliedAt = nowUtc();
            for (String pointer : newLegacyPointers) {
                repository.insertRedactionOverlay(
                    UUID.randomUUID().toString(),
                    eventId,
                    targetEvent.getChainPosition(),
                    pointer,
                    MASK_MODE,
                    request.getReasonCode().trim(),
                    normalizeNullable(request.getApprovalRef()),
                    request.getRequestedBy().trim(),
                    normalizeNullable(request.getApprovedBy()),
                    appliedAt,
                    null,
                    true
                );
            }

            String redactionMode = determineRedactionMode(newLegacyPointers, encryptionKeysToDestroy);
            certificateEventId = createCertificateEvent(targetEvent, appliedPointers, encryptionKeysToDestroy, request, redactionMode, appliedAt);
            repository.linkRedactionOverlaysToCertificate(eventId, newLegacyPointers, certificateEventId);
            for (AuditEventEncryptionKey keyRecord : encryptionKeysToDestroy) {
                repository.destroyEncryptionKey(
                    keyRecord.getKeyRef(),
                    appliedAt,
                    request.getRequestedBy().trim(),
                    request.getReasonCode().trim(),
                    normalizeNullable(request.getApprovalRef()),
                    certificateEventId
                );
            }
        }

        return new RedactionResponse(
            targetEvent.getId(),
            targetEvent.getChainPosition(),
            appliedPointers,
            alreadyRedactedPointers,
            certificateEventId,
            appliedPointers.isEmpty() ? "ALREADY_REDACTED" : "APPLIED"
        );
    }

    private String createCertificateEvent(
        AuditEvent targetEvent,
        List<String> appliedPointers,
        List<AuditEventEncryptionKey> destroyedKeys,
        RedactionRequest request,
        String redactionMode,
        String appliedAt
    ) {
        AuditEventCreateRequest certificateRequest = new AuditEventCreateRequest();
        certificateRequest.setEventType(REDACTION_EVENT_TYPE);
        certificateRequest.setActorId("redaction-service");
        certificateRequest.setResourceType("REDACTION");
        certificateRequest.setResourceId(targetEvent.getId());

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("targetEventId", targetEvent.getId());
        payload.put("targetChainPosition", targetEvent.getChainPosition());
        ArrayNode pointerArray = objectMapper.createArrayNode();
        for (String pointer : appliedPointers.stream().sorted().toList()) {
            pointerArray.add(pointer);
        }
        payload.set("jsonPointers", pointerArray);
        payload.put("reasonCode", request.getReasonCode().trim());
        payload.put("approvalRef", normalizeForCertificate(request.getApprovalRef()));
        payload.put("requestedBy", request.getRequestedBy().trim());
        payload.put("approvedBy", normalizeForCertificate(request.getApprovedBy()));
        payload.put("redactionMode", redactionMode);
        payload.put("appliedAt", appliedAt);

        if (!destroyedKeys.isEmpty()) {
            ArrayNode keyRefs = objectMapper.createArrayNode();
            for (String keyRef : destroyedKeys.stream().map(AuditEventEncryptionKey::getKeyRef).distinct().sorted().toList()) {
                keyRefs.add(keyRef);
            }
            payload.set("keyRefs", keyRefs);
            payload.put("encryptionVersion", destroyedKeys.get(0).getEncryptionVersion());
            payload.put("keyStatus", "DESTROYED");
        }

        certificateRequest.setPayload(payload);
        certificateRequest.setTimestamp(appliedAt);
        return auditEventService.createEvent(certificateRequest).getId();
    }

    private void validateRequest(RedactionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        if (request.getJsonPointers() == null || request.getJsonPointers().isEmpty()) {
            throw new IllegalArgumentException("jsonPointers is required");
        }
        if (request.getReasonCode() == null || request.getReasonCode().trim().isEmpty()) {
            throw new IllegalArgumentException("reasonCode is required");
        }
        if (request.getRequestedBy() == null || request.getRequestedBy().trim().isEmpty()) {
            throw new IllegalArgumentException("requestedBy is required");
        }
        if (redactionProperties.isRequireApproval()) {
            if (request.getApprovalRef() == null || request.getApprovalRef().trim().isEmpty()) {
                throw new IllegalArgumentException("approvalRef is required");
            }
            if (request.getApprovedBy() == null || request.getApprovedBy().trim().isEmpty()) {
                throw new IllegalArgumentException("approvedBy is required");
            }
        }
    }

    private Map<String, AuditEventEncryptionKey> mapKeysByPointer(List<AuditEventEncryptionKey> encryptionKeys) {
        Map<String, AuditEventEncryptionKey> keysByPointer = new LinkedHashMap<>();
        for (AuditEventEncryptionKey keyRecord : encryptionKeys) {
            for (String pointer : keyRecord.getEncryptedPointers()) {
                keysByPointer.put(pointer, keyRecord);
            }
        }
        return keysByPointer;
    }

    private void validateRequestedPointerGroup(List<String> requestedPointers, AuditEventEncryptionKey keyRecord) {
        if (keyRecord.getEncryptedPointers().stream().allMatch(requestedPointers::contains)) {
            return;
        }
        throw new IllegalArgumentException(
            "Request must include the complete encrypted pointer group for keyRef " + keyRecord.getKeyRef()
        );
    }

    private void validateReservedPointer(String pointer) {
        if (RESERVED_POINTERS.contains(pointer)) {
            throw new IllegalArgumentException("Pointer is reserved and cannot be redacted: " + pointer);
        }
    }

    private boolean isLegacyAllowed(String pointer) {
        Set<String> allowList = new LinkedHashSet<>();
        for (String entry : redactionProperties.getAllowList().split(",")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                allowList.add(JsonPointerUtils.canonicalize(trimmed));
            }
        }
        return allowList.contains(pointer);
    }

    private String determineRedactionMode(List<String> newLegacyPointers, List<AuditEventEncryptionKey> encryptionKeysToDestroy) {
        if (!newLegacyPointers.isEmpty() && !encryptionKeysToDestroy.isEmpty()) {
            return HYBRID_MODE;
        }
        if (!encryptionKeysToDestroy.isEmpty()) {
            return CRYPTO_ERASURE_MODE;
        }
        return MASK_MODE;
    }

    private String normalizeNullable(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private String normalizeForCertificate(String value) {
        return value == null ? "" : value.trim();
    }

    private String nowUtc() {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME
            .format(Instant.now(clock).atOffset(ZoneOffset.UTC))
            .replace("+00:00", "Z");
    }
}
