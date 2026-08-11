package com.auditlog.service.api.dto;

import com.auditlog.service.domain.AuditEvent;
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
    String hashVersion
) {
    public static AuditEventResponse fromDomain(AuditEvent event) {
        return new AuditEventResponse(
            event.getId(),
            event.getChainPosition(),
            event.getEventType(),
            event.getActorId(),
            event.getResourceType(),
            event.getResourceId(),
            event.getPayload(),
            event.getTimestamp(),
            event.getIngestedAt(),
            event.getContentHash(),
            event.getPreviousHash(),
            event.getChainHash(),
            event.getHashVersion()
        );
    }
}
