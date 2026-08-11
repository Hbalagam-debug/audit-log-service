package com.auditlog.service.domain;

public class RedactionOverlay {
    private final String id;
    private final String eventId;
    private final long eventChainPosition;
    private final String jsonPointer;
    private final String redactionMode;
    private final String reasonCode;
    private final String approvalRef;
    private final String requestedBy;
    private final String approvedBy;
    private final String appliedAt;
    private final String certificateEventId;
    private final boolean active;

    public RedactionOverlay(
        String id,
        String eventId,
        long eventChainPosition,
        String jsonPointer,
        String redactionMode,
        String reasonCode,
        String approvalRef,
        String requestedBy,
        String approvedBy,
        String appliedAt,
        String certificateEventId,
        boolean active
    ) {
        this.id = id;
        this.eventId = eventId;
        this.eventChainPosition = eventChainPosition;
        this.jsonPointer = jsonPointer;
        this.redactionMode = redactionMode;
        this.reasonCode = reasonCode;
        this.approvalRef = approvalRef;
        this.requestedBy = requestedBy;
        this.approvedBy = approvedBy;
        this.appliedAt = appliedAt;
        this.certificateEventId = certificateEventId;
        this.active = active;
    }

    public String getId() { return id; }
    public String getEventId() { return eventId; }
    public long getEventChainPosition() { return eventChainPosition; }
    public String getJsonPointer() { return jsonPointer; }
    public String getRedactionMode() { return redactionMode; }
    public String getReasonCode() { return reasonCode; }
    public String getApprovalRef() { return approvalRef; }
    public String getRequestedBy() { return requestedBy; }
    public String getApprovedBy() { return approvedBy; }
    public String getAppliedAt() { return appliedAt; }
    public String getCertificateEventId() { return certificateEventId; }
    public boolean isActive() { return active; }
}
