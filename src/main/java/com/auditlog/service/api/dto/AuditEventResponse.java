package com.auditlog.service.api.dto;

import com.auditlog.service.domain.AuditEvent;
import java.util.List;
import tools.jackson.databind.JsonNode;

public record AuditEventResponse(
    String id,
    long chainPosition,
    String eventType,
    String actorId,
    String resourceType,
    String resourceId,
    JsonNode payload,
    String timestamp,
    String ingestedAt,
    String contentHash,
    String previousHash,
    String chainHash,
    String hashVersion,
    boolean redacted,
    List<String> redactedPointers
) {
    public static AuditEventResponse fromDomain(AuditEvent event) {
        return fromDomain(event, event.getPayload(), List.of());
    }

    public static AuditEventResponse fromDomain(AuditEvent event, JsonNode payload, List<String> redactedPointers) {
        return new AuditEventResponse(
            event.getId(),
            event.getChainPosition(),
            event.getEventType(),
            event.getActorId(),
            event.getResourceType(),
            event.getResourceId(),
            payload,
            event.getTimestamp(),
            event.getIngestedAt(),
            event.getContentHash(),
            event.getPreviousHash(),
            event.getChainHash(),
            event.getHashVersion(),
            !redactedPointers.isEmpty(),
            redactedPointers
        );
    }
}
