package com.auditlog.service.service;

import com.auditlog.service.api.dto.AuditEventCreateRequest;
import com.auditlog.service.api.dto.RedactionRequest;
import com.auditlog.service.api.dto.RedactionResponse;
import com.auditlog.service.config.RedactionProperties;
import com.auditlog.service.domain.AuditEvent;
import com.auditlog.service.domain.RedactionOverlay;
import com.auditlog.service.repository.AuditEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;

@Service
public class RedactionService {
    private static final String REDACTION_EVENT_TYPE = "REDACTION_APPLIED";
    private static final String REDACTION_MODE = "MASK";
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
    private final Clock clock;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    public RedactionService(
        AuditEventRepository repository,
        AuditEventService auditEventService,
        RedactionProperties redactionProperties,
        Clock clock
    ) {
        this.repository = repository;
        this.auditEventService = auditEventService;
        this.redactionProperties = redactionProperties;
        this.clock = clock;
    }

    @Transactional
    public RedactionResponse applyRedaction(String eventId, RedactionRequest request) {
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

        AuditEvent targetEvent = repository.findById(eventId)
            .orElseThrow(() -> new ResourceNotFoundException("Event not found: " + eventId));

        List<String> normalizedPointers = normalizePointers(request.getJsonPointers());
        validatePointers(targetEvent, normalizedPointers);

        List<RedactionOverlay> existingActiveOverlays = repository.findActiveOverlaysForEventId(eventId);
        Set<String> existingPointers = existingActiveOverlays.stream()
            .map(RedactionOverlay::getJsonPointer)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        List<String> newPointers = normalizedPointers.stream()
            .filter(pointer -> !existingPointers.contains(pointer))
            .distinct()
            .sorted()
            .toList();
        List<String> alreadyRedactedPointers = normalizedPointers.stream()
            .filter(existingPointers::contains)
            .distinct()
            .sorted()
            .toList();

        String certificateEventId = null;
        if (!newPointers.isEmpty()) {
            for (String pointer : newPointers) {
                repository.insertRedactionOverlay(
                    UUID.randomUUID().toString(),
                    eventId,
                    targetEvent.getChainPosition(),
                    pointer,
                    REDACTION_MODE,
                    request.getReasonCode().trim(),
                    request.getApprovalRef() == null ? null : request.getApprovalRef().trim(),
                    request.getRequestedBy().trim(),
                    request.getApprovedBy() == null ? null : request.getApprovedBy().trim(),
                    nowUtc(),
                    null,
                    true
                );
            }

            certificateEventId = createCertificateEvent(targetEvent, newPointers, request);

            repository.linkRedactionOverlaysToCertificate(eventId, newPointers, certificateEventId);
        }

        return new RedactionResponse(
            targetEvent.getId(),
            targetEvent.getChainPosition(),
            newPointers,
            alreadyRedactedPointers,
            certificateEventId,
            "APPLIED"
        );
    }

    private String createCertificateEvent(AuditEvent targetEvent, List<String> newPointers, RedactionRequest request) {
        AuditEventCreateRequest certificateRequest = new AuditEventCreateRequest();
        certificateRequest.setEventType(REDACTION_EVENT_TYPE);
        certificateRequest.setActorId("redaction-service");
        certificateRequest.setResourceType("REDACTION");
        certificateRequest.setResourceId(targetEvent.getId());

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("targetEventId", targetEvent.getId());
        payload.put("targetChainPosition", targetEvent.getChainPosition());
        ArrayNode pointerArray = objectMapper.createArrayNode();
        for (String pointer : newPointers) {
            pointerArray.add(pointer);
        }
        payload.set("jsonPointers", pointerArray);
        payload.put("reasonCode", request.getReasonCode().trim());
        payload.put("approvalRef", request.getApprovalRef() == null ? "" : request.getApprovalRef().trim());
        payload.put("requestedBy", request.getRequestedBy().trim());
        payload.put("approvedBy", request.getApprovedBy() == null ? "" : request.getApprovedBy().trim());
        payload.put("redactionMode", REDACTION_MODE);
        payload.put("appliedAt", nowUtc());

        certificateRequest.setPayload(payload);
        certificateRequest.setTimestamp(nowUtc());
        AuditEvent created = auditEventService.createEvent(certificateRequest);
        return created.getId();
    }

    private List<String> normalizePointers(List<String> pointers) {
        Set<String> seen = new LinkedHashSet<>();
        for (String pointer : pointers) {
            if (pointer == null) {
                throw new IllegalArgumentException("jsonPointers must not contain null values");
            }
            String trimmed = pointer.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("jsonPointers must not be empty");
            }
            if (!trimmed.startsWith("/")) {
                throw new IllegalArgumentException("Invalid pointer syntax: " + pointer);
            }
            List<String> tokens = parsePointer(trimmed);
            String canonical = "/" + String.join("/", tokens);
            seen.add(canonical);
        }
        return new ArrayList<>(seen).stream().sorted().toList();
    }

    private void validatePointers(AuditEvent targetEvent, List<String> pointers) {
        JsonNode payload = targetEvent.getPayload();
        for (String pointer : pointers) {
            if (pointer.startsWith("/")) {
                if (RESERVED_POINTERS.contains(pointer)) {
                    throw new IllegalArgumentException("Pointer is reserved and cannot be redacted: " + pointer);
                }
            }
            if (!isAllowed(pointer)) {
                throw new IllegalArgumentException("Pointer is not allowed by the current redaction policy: " + pointer);
            }
            if (!pointerExists(payload, pointer)) {
                throw new IllegalArgumentException("Pointer does not exist in payload: " + pointer);
            }
        }
    }

    private boolean isAllowed(String pointer) {
        Set<String> allowList = new LinkedHashSet<>();
        for (String entry : redactionProperties.getAllowList().split(",")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                allowList.add(trimmed);
            }
        }
        return allowList.contains(pointer);
    }

    private boolean pointerExists(JsonNode root, String pointer) {
        List<String> tokens = parsePointer(pointer);
        JsonNode current = root;
        for (String token : tokens) {
            if (current == null || current.isMissingNode()) {
                return false;
            }
            if (current.isObject()) {
                if (!current.has(token)) {
                    return false;
                }
                current = current.get(token);
            } else if (current.isArray()) {
                try {
                    int index = Integer.parseInt(token);
                    if (index < 0 || index >= current.size()) {
                        return false;
                    }
                    current = current.get(index);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else {
                return false;
            }
        }
        return true;
    }

    private List<String> parsePointer(String pointer) {
        if (pointer.equals("/")) {
            return List.of("");
        }
        List<String> tokens = new ArrayList<>();
        String remainder = pointer.startsWith("/") ? pointer.substring(1) : pointer;
        if (remainder.isEmpty()) {
            return tokens;
        }
        String[] segments = remainder.split("/");
        for (String segment : segments) {
            if (segment.isEmpty()) {
                tokens.add("");
                continue;
            }
            StringBuilder tokenBuilder = new StringBuilder();
            for (int i = 0; i < segment.length(); i++) {
                char ch = segment.charAt(i);
                if (ch == '~') {
                    if (i + 1 >= segment.length()) {
                        throw new IllegalArgumentException("Invalid escape sequence in pointer: " + pointer);
                    }
                    char next = segment.charAt(i + 1);
                    if (next == '0') {
                        tokenBuilder.append('~');
                    } else if (next == '1') {
                        tokenBuilder.append('/');
                    } else {
                        throw new IllegalArgumentException("Invalid escape sequence in pointer: " + pointer);
                    }
                    i++;
                } else {
                    tokenBuilder.append(ch);
                }
            }
            tokens.add(tokenBuilder.toString());
        }
        return tokens;
    }

    private String nowUtc() {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.now(clock).atOffset(ZoneOffset.UTC)).replace("+00:00", "Z");
    }
}
