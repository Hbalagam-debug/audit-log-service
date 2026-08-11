package com.auditlog.service.domain;

import tools.jackson.databind.JsonNode;

public class AuditEvent {
    private final String id;
    private final long chainPosition;
    private final String eventType;
    private final String actorId;
    private final String resourceType;
    private final String resourceId;
    private final JsonNode payload;
    private final String timestamp;
    private final String ingestedAt;
    private final String contentHash;
    private final String previousHash;
    private final String chainHash;
    private final String hashVersion;

    public AuditEvent(
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
        this.id = id;
        this.chainPosition = chainPosition;
        this.eventType = eventType;
        this.actorId = actorId;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.payload = payload;
        this.timestamp = timestamp;
        this.ingestedAt = ingestedAt;
        this.contentHash = contentHash;
        this.previousHash = previousHash;
        this.chainHash = chainHash;
        this.hashVersion = hashVersion;
    }

    public String getId() { return id; }
    public long getChainPosition() { return chainPosition; }
    public String getEventType() { return eventType; }
    public String getActorId() { return actorId; }
    public String getResourceType() { return resourceType; }
    public String getResourceId() { return resourceId; }
    public JsonNode getPayload() { return payload; }
    public String getTimestamp() { return timestamp; }
    public String getIngestedAt() { return ingestedAt; }
    public String getContentHash() { return contentHash; }
    public String getPreviousHash() { return previousHash; }
    public String getChainHash() { return chainHash; }
    public String getHashVersion() { return hashVersion; }
}
