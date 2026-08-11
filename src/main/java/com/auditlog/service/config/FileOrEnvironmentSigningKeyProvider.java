package com.auditlog.service.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.PublicKey;

@Component
public class FileOrEnvironmentSigningKeyProvider implements SigningKeyProvider {
    static final String PRIVATE_KEY_FILE_ENV = "AUDIT_EXPORT_SIGNING_PRIVATE_KEY_FILE";
    static final String PRIVATE_KEY_BASE64_ENV = "AUDIT_EXPORT_SIGNING_PRIVATE_KEY_BASE64";
    static final String PUBLIC_KEY_FILE_ENV = "AUDIT_EXPORT_SIGNING_PUBLIC_KEY_FILE";
    static final String PUBLIC_KEY_BASE64_ENV = "AUDIT_EXPORT_SIGNING_PUBLIC_KEY_BASE64";
    static final String KEY_ID_ENV = "AUDIT_EXPORT_SIGNING_KEY_ID";

    private final ExportProperties.SigningProperties signingProperties;
    private PrivateKey privateKey;
    private PublicKey publicKey;

    @Autowired
    public FileOrEnvironmentSigningKeyProvider(ExportProperties exportProperties) {
        this(exportProperties.getSigning());
    }

    FileOrEnvironmentSigningKeyProvider(ExportProperties.SigningProperties signingProperties) {
        this.signingProperties = signingProperties;
    }

    @PostConstruct
    public void validateConfiguration() {
        if (!signingProperties.isEnabled()) {
            privateKey = null;
            publicKey = null;
            return;
        }

        if (signingProperties.getKeyId() == null || signingProperties.getKeyId().isBlank()) {
            throw new IllegalStateException(
                "Export signing is enabled but " + KEY_ID_ENV + " is not configured"
            );
        }

        privateKey = ExportSignatureSupport.decodePrivateKey(
            resolveKeyMaterial(
                signingProperties.getPrivateKeyFile(),
                PRIVATE_KEY_FILE_ENV,
                signingProperties.getPrivateKeyBase64(),
                PRIVATE_KEY_BASE64_ENV
            ),
            PRIVATE_KEY_FILE_ENV + "/" + PRIVATE_KEY_BASE64_ENV
        );
        publicKey = ExportSignatureSupport.decodePublicKey(
            resolveKeyMaterial(
                signingProperties.getPublicKeyFile(),
                PUBLIC_KEY_FILE_ENV,
                signingProperties.getPublicKeyBase64(),
                PUBLIC_KEY_BASE64_ENV
            ),
            PUBLIC_KEY_FILE_ENV + "/" + PUBLIC_KEY_BASE64_ENV
        );
        ExportSignatureSupport.validateKeyPair(privateKey, publicKey);
    }

    @Override
    public PrivateKey loadPrivateKey() {
        if (!signingProperties.isEnabled()) {
            throw new IllegalStateException("Export signing is disabled");
        }
        if (privateKey == null) {
            throw new IllegalStateException("Export signing private key is not available");
        }
        return privateKey;
    }

    @Override
    public PublicKey loadPublicKey() {
        if (!signingProperties.isEnabled()) {
            throw new IllegalStateException("Export signing is disabled");
        }
        if (publicKey == null) {
            throw new IllegalStateException("Export signing public key is not available");
        }
        return publicKey;
    }

    @Override
    public String getKeyId() {
        return signingProperties.getKeyId();
    }

    private String resolveKeyMaterial(String filePath, String fileEnvName, String base64Value, String base64EnvName) {
        if (hasText(filePath)) {
            return readKeyFile(filePath.trim(), fileEnvName);
        }
        if (hasText(base64Value)) {
            return base64Value.trim();
        }
        throw new IllegalStateException(
            "Export signing is enabled but neither " + fileEnvName + " nor " + base64EnvName + " is configured"
        );
    }

    private String readKeyFile(String filePath, String fileEnvName) {
        try {
            String fileContents = Files.readString(Path.of(filePath), StandardCharsets.UTF_8).trim();
            if (!hasText(fileContents)) {
                throw new IllegalStateException(
                    "Export signing key file configured by " + fileEnvName + " is empty"
                );
            }
            return fileContents;
        } catch (IOException ex) {
            throw new IllegalStateException(
                "Failed to read export signing key file configured by " + fileEnvName,
                ex
            );
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
