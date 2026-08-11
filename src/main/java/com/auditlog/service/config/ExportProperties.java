package com.auditlog.service.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.security.PrivateKey;
import java.security.PublicKey;

@Component
@ConfigurationProperties(prefix = "audit.export")
@Validated
public class ExportProperties {
    private int maxRecords = 10000;
    private SigningProperties signing = new SigningProperties();

    @PostConstruct
    public void validate() {
        if (maxRecords < 1) {
            throw new IllegalStateException("audit.export.max-records must be greater than zero");
        }
        if (signing == null) {
            throw new IllegalStateException("audit.export.signing configuration is required");
        }
        signing.validate();
    }

    public int getMaxRecords() {
        return maxRecords;
    }

    public void setMaxRecords(int maxRecords) {
        this.maxRecords = maxRecords;
    }

    public SigningProperties getSigning() {
        return signing;
    }

    public void setSigning(SigningProperties signing) {
        this.signing = signing;
    }

    public static class SigningProperties {
        private boolean enabled = true;
        private String algorithm = ExportSignatureSupport.SUPPORTED_SIGNATURE_ALGORITHM;
        private String keyId = "export-key-2026-01";
        private String privateKeyBase64 = "";
        private String publicKeyBase64 = "";
        private transient PrivateKey resolvedPrivateKey;
        private transient PublicKey resolvedPublicKey;
        private transient String normalizedPublicKeyBase64;

        public void validate() {
            if (!enabled) {
                resolvedPrivateKey = null;
                resolvedPublicKey = null;
                normalizedPublicKeyBase64 = null;
                return;
            }

            if (!ExportSignatureSupport.SUPPORTED_SIGNATURE_ALGORITHM.equals(algorithm)) {
                throw new IllegalStateException("Unsupported audit.export.signing.algorithm: " + algorithm);
            }
            if (keyId == null || keyId.isBlank()) {
                throw new IllegalStateException("audit.export.signing.key-id must be configured when audit.export.signing.enabled=true");
            }
            if (privateKeyBase64 == null || privateKeyBase64.isBlank()) {
                throw new IllegalStateException("audit.export.signing.private-key-base64 must be configured when audit.export.signing.enabled=true");
            }
            if (publicKeyBase64 == null || publicKeyBase64.isBlank()) {
                throw new IllegalStateException("audit.export.signing.public-key-base64 must be configured when audit.export.signing.enabled=true");
            }

            resolvedPrivateKey = ExportSignatureSupport.decodePrivateKey(privateKeyBase64, "audit.export.signing.private-key-base64");
            resolvedPublicKey = ExportSignatureSupport.decodePublicKey(publicKeyBase64, "audit.export.signing.public-key-base64");
            normalizedPublicKeyBase64 = ExportSignatureSupport.encodePublicKeyBase64(resolvedPublicKey);
            ExportSignatureSupport.validateKeyPair(resolvedPrivateKey, resolvedPublicKey);
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getAlgorithm() {
            return algorithm;
        }

        public void setAlgorithm(String algorithm) {
            this.algorithm = algorithm;
        }

        public String getKeyId() {
            return keyId;
        }

        public void setKeyId(String keyId) {
            this.keyId = keyId;
        }

        public String getPrivateKeyBase64() {
            return privateKeyBase64;
        }

        public void setPrivateKeyBase64(String privateKeyBase64) {
            this.privateKeyBase64 = privateKeyBase64;
        }

        public String getPublicKeyBase64() {
            return publicKeyBase64;
        }

        public void setPublicKeyBase64(String publicKeyBase64) {
            this.publicKeyBase64 = publicKeyBase64;
        }

        public PrivateKey getResolvedPrivateKey() {
            return resolvedPrivateKey;
        }

        public PublicKey getResolvedPublicKey() {
            return resolvedPublicKey;
        }

        public String getNormalizedPublicKeyBase64() {
            return normalizedPublicKeyBase64;
        }
    }
}
