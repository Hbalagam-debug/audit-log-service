package com.auditlog.service.service;

import com.auditlog.service.api.dto.AuditEventResponse;
import com.auditlog.service.config.RedactionProperties;
import com.auditlog.service.domain.AuditEvent;
import com.auditlog.service.domain.AuditEventEncryptionKey;
import com.auditlog.service.domain.RedactionOverlay;
import com.auditlog.service.repository.AuditEventRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

@Service
public class RedactionViewService {
    private final AuditEventRepository repository;
    private final RedactionProperties redactionProperties;
    private final PayloadEncryptionService payloadEncryptionService;

    public RedactionViewService(AuditEventRepository repository, RedactionProperties redactionProperties) {
        this(repository, redactionProperties, PayloadEncryptionService.disabled());
    }

    @Autowired
    public RedactionViewService(
        AuditEventRepository repository,
        RedactionProperties redactionProperties,
        PayloadEncryptionService payloadEncryptionService
    ) {
        this.repository = repository;
        this.redactionProperties = redactionProperties;
        this.payloadEncryptionService = payloadEncryptionService;
    }

    public List<AuditEventResponse> maskEvents(List<AuditEvent> events) {
        if (events.isEmpty()) {
            return List.of();
        }

        List<String> eventIds = events.stream().map(AuditEvent::getId).toList();
        List<RedactionOverlay> overlays = repository.findActiveOverlaysForEventIds(eventIds);
        List<AuditEventEncryptionKey> encryptionKeys = repository.findEncryptionKeysForEventIds(eventIds);
        Map<String, List<RedactionOverlay>> overlaysByEvent = overlays.stream()
            .collect(Collectors.groupingBy(RedactionOverlay::getEventId, LinkedHashMap::new, Collectors.toList()));
        Map<String, List<AuditEventEncryptionKey>> encryptionKeysByEvent = encryptionKeys.stream()
            .collect(Collectors.groupingBy(AuditEventEncryptionKey::getEventId, LinkedHashMap::new, Collectors.toList()));

        List<AuditEventResponse> responses = new ArrayList<>();
        for (AuditEvent event : events) {
            List<RedactionOverlay> eventOverlays = overlaysByEvent.getOrDefault(event.getId(), List.of());
            PayloadEncryptionService.PayloadViewResult payloadViewResult = payloadEncryptionService.renderPayloadForResponse(
                event,
                encryptionKeysByEvent.getOrDefault(event.getId(), List.of()),
                redactionProperties.getMaskValue()
            );
            JsonNode maskedPayload = payloadViewResult.payload();
            List<String> pointerList = eventOverlays.stream()
                .map(RedactionOverlay::getJsonPointer)
                .distinct()
                .sorted()
                .toList();
            for (String pointer : pointerList) {
                applyMask(maskedPayload, pointer);
            }
            List<String> redactedPointers = new ArrayList<>(payloadViewResult.destroyedPointers());
            redactedPointers.addAll(pointerList);
            List<String> mergedPointers = new ArrayList<>(new LinkedHashSet<>(redactedPointers)).stream().sorted().toList();
            responses.add(AuditEventResponse.fromDomain(event, maskedPayload, mergedPointers));
        }
        return responses;
    }

    public AuditEventResponse maskEvent(AuditEvent event) {
        return maskEvents(List.of(event)).get(0);
    }

    private void applyMask(JsonNode root, String pointer) {
        JsonNode existing = JsonPointerUtils.getNode(root, pointer);
        if (existing == null) {
            return;
        }
        JsonPointerUtils.setValue(root, pointer, tools.jackson.databind.node.JsonNodeFactory.instance.textNode(redactionProperties.getMaskValue()));
    }
}
