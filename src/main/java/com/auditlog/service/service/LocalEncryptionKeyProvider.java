package com.auditlog.service.service;

import com.auditlog.service.config.EncryptionProperties;
import com.auditlog.service.domain.AuditEventEncryptionKey;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Service
public class LocalEncryptionKeyProvider implements EncryptionKeyProvider {
    private static final String WRAP_ALGORITHM = "AES-256-GCM-WRAP";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int NONCE_LENGTH_BYTES = 12;
    private static final String DESTRUCTION_EVIDENCE = "LOCAL_DB_WRAPPED_KEY_REMOVED";

    private final SecretKeySpec masterKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public LocalEncryptionKeyProvider(EncryptionProperties encryptionProperties) {
        byte[] decoded = Base64.getDecoder().decode(encryptionProperties.getMasterKeyBase64());
        this.masterKey = new SecretKeySpec(decoded, "AES");
        Arrays.fill(decoded, (byte) 0);
    }

    @Override
    public GeneratedKeyMaterial generateDataKey(String keyRef, String encryptionVersion) {
        byte[] plaintextDek = new byte[32];
        secureRandom.nextBytes(plaintextDek);
        byte[] wrapNonce = new byte[NONCE_LENGTH_BYTES];
        secureRandom.nextBytes(wrapNonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, wrapNonce));
            cipher.updateAAD(buildWrapAad(keyRef, encryptionVersion));
            byte[] wrapped = cipher.doFinal(plaintextDek);
            return new GeneratedKeyMaterial(
                plaintextDek,
                WRAP_ALGORITHM,
                Base64.getEncoder().encodeToString(wrapped),
                Base64.getEncoder().encodeToString(wrapNonce)
            );
        } catch (Exception ex) {
            Arrays.fill(plaintextDek, (byte) 0);
            throw new RuntimeException("Failed to wrap data encryption key", ex);
        }
    }

    @Override
    public byte[] unwrapActiveKey(AuditEventEncryptionKey keyRecord) {
        if (!keyRecord.isActive() || keyRecord.getWrappedDek() == null || keyRecord.getWrapNonce() == null) {
            throw new IllegalStateException("Active key material is unavailable for keyRef " + keyRecord.getKeyRef());
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                Cipher.DECRYPT_MODE,
                masterKey,
                new GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.getDecoder().decode(keyRecord.getWrapNonce()))
            );
            cipher.updateAAD(buildWrapAad(keyRecord.getKeyRef(), keyRecord.getEncryptionVersion()));
            return cipher.doFinal(Base64.getDecoder().decode(keyRecord.getWrappedDek()));
        } catch (Exception ex) {
            throw new RuntimeException("Failed to unwrap data encryption key", ex);
        }
    }

    @Override
    public String getDestructionEvidence() {
        return DESTRUCTION_EVIDENCE;
    }

    private byte[] buildWrapAad(String keyRef, String encryptionVersion) {
        return ("dek-wrap:" + encryptionVersion + ":" + keyRef).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
