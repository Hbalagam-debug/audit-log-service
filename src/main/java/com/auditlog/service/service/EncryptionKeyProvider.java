package com.auditlog.service.service;

import com.auditlog.service.domain.AuditEventEncryptionKey;

public interface EncryptionKeyProvider {
    GeneratedKeyMaterial generateDataKey(String keyRef, String encryptionVersion);

    byte[] unwrapActiveKey(AuditEventEncryptionKey keyRecord);

    String getDestructionEvidence();

    record GeneratedKeyMaterial(
        byte[] plaintextDek,
        String wrapAlgorithm,
        String wrappedDek,
        String wrapNonce
    ) {
    }
}
