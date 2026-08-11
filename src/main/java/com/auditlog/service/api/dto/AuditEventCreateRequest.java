package com.auditlog.service.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

public class AuditEventCreateRequest {
    @NotBlank(message = "eventType is required")
    @Size(min = 1, max = 128, message = "eventType must be 1-128 characters")
    private String eventType;

    @NotBlank(message = "actorId is required")
    @Size(min = 1, max = 256, message = "actorId must be 1-256 characters")
    private String actorId;

    @NotBlank(message = "resourceType is required")
    @Size(min = 1, max = 128, message = "resourceType must be 1-128 characters")
    private String resourceType;

    @NotBlank(message = "resourceId is required")
    @Size(min = 1, max = 512, message = "resourceId must be 1-512 characters")
    private String resourceId;

    @NotNull(message = "payload is required")
    private JsonNode payload;

    private String timestamp;

    public AuditEventCreateRequest() {}

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getActorId() { return actorId; }
    public void setActorId(String actorId) { this.actorId = actorId; }

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }

    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }

    public JsonNode getPayload() { return payload; }
    public void setPayload(JsonNode payload) { this.payload = payload; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
}
