package com.auditlog.service.domain;

import java.util.List;

public class AuditEventEncryptionKey {
    private final String keyRef;
    private final String eventId;
    private final List<String> encryptedPointers;
    private final String wrapAlgorithm;
    private final String wrappedDek;
    private final String wrapNonce;
    private final String status;
    private final String createdAt;
    private final String destroyedAt;
    private final String destroyedBy;
    private final String destructionReason;
    private final String approvalRef;
    private final String destructionCertificateEventId;
    private final String encryptionVersion;

    public AuditEventEncryptionKey(
        String keyRef,
        String eventId,
        List<String> encryptedPointers,
        String wrapAlgorithm,
        String wrappedDek,
        String wrapNonce,
        String status,
        String createdAt,
        String destroyedAt,
        String destroyedBy,
        String destructionReason,
        String approvalRef,
        String destructionCertificateEventId,
        String encryptionVersion
    ) {
        this.keyRef = keyRef;
        this.eventId = eventId;
        this.encryptedPointers = encryptedPointers;
        this.wrapAlgorithm = wrapAlgorithm;
        this.wrappedDek = wrappedDek;
        this.wrapNonce = wrapNonce;
        this.status = status;
        this.createdAt = createdAt;
        this.destroyedAt = destroyedAt;
        this.destroyedBy = destroyedBy;
        this.destructionReason = destructionReason;
        this.approvalRef = approvalRef;
        this.destructionCertificateEventId = destructionCertificateEventId;
        this.encryptionVersion = encryptionVersion;
    }

    public String getKeyRef() {
        return keyRef;
    }

    public String getEventId() {
        return eventId;
    }

    public List<String> getEncryptedPointers() {
        return encryptedPointers;
    }

    public String getWrapAlgorithm() {
        return wrapAlgorithm;
    }

    public String getWrappedDek() {
        return wrappedDek;
    }

    public String getWrapNonce() {
        return wrapNonce;
    }

    public String getStatus() {
        return status;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getDestroyedAt() {
        return destroyedAt;
    }

    public String getDestroyedBy() {
        return destroyedBy;
    }

    public String getDestructionReason() {
        return destructionReason;
    }

    public String getApprovalRef() {
        return approvalRef;
    }

    public String getDestructionCertificateEventId() {
        return destructionCertificateEventId;
    }

    public String getEncryptionVersion() {
        return encryptionVersion;
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    public boolean isDestroyed() {
        return "DESTROYED".equals(status);
    }
}
