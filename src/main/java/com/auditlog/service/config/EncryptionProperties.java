package com.auditlog.service.config;

import com.auditlog.service.service.JsonPointerUtils;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
@ConfigurationProperties(prefix = "audit.encryption")
@Validated
public class EncryptionProperties {
    private static final String SUPPORTED_VERSION = "enc-v1";
    private static final String SUPPORTED_ALGORITHM = "AES-256-GCM";

    private boolean enabled = true;
    private String formatVersion = SUPPORTED_VERSION;
    private String algorithm = SUPPORTED_ALGORITHM;
    private List<String> sensitivePointers = new ArrayList<>(List.of("/accountNumber", "/personalIdentifier"));
    private String masterKeyBase64 = "";

    @PostConstruct
    public void validate() {
        if (!enabled) {
            return;
        }
        if (!SUPPORTED_VERSION.equals(formatVersion)) {
            throw new IllegalStateException("Unsupported audit.encryption.format-version: " + formatVersion);
        }
        if (!SUPPORTED_ALGORITHM.equals(algorithm)) {
            throw new IllegalStateException("Unsupported audit.encryption.algorithm: " + algorithm);
        }
        if (sensitivePointers == null || sensitivePointers.isEmpty()) {
            throw new IllegalStateException("audit.encryption.sensitive-pointers must not be empty when encryption is enabled");
        }

        List<String> normalizedPointers = getNormalizedSensitivePointers();
        JsonPointerUtils.validateNoOverlaps(normalizedPointers);

        if (masterKeyBase64 == null || masterKeyBase64.isBlank()) {
            throw new IllegalStateException("audit.encryption.master-key-base64 must be configured when audit.encryption.enabled=true");
        }

        try {
            byte[] decoded = Base64.getDecoder().decode(masterKeyBase64);
            if (decoded.length != 32) {
                throw new IllegalStateException("audit.encryption.master-key-base64 must decode to exactly 32 bytes");
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("audit.encryption.master-key-base64 must be valid Base64 for a 32-byte AES key");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getFormatVersion() {
        return formatVersion;
    }

    public void setFormatVersion(String formatVersion) {
        this.formatVersion = formatVersion;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }

    public List<String> getSensitivePointers() {
        return sensitivePointers;
    }

    public void setSensitivePointers(List<String> sensitivePointers) {
        this.sensitivePointers = sensitivePointers;
    }

    public String getMasterKeyBase64() {
        return masterKeyBase64;
    }

    public void setMasterKeyBase64(String masterKeyBase64) {
        this.masterKeyBase64 = masterKeyBase64;
    }

    public List<String> getNormalizedSensitivePointers() {
        if (sensitivePointers == null) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String pointer : sensitivePointers) {
            normalized.add(JsonPointerUtils.canonicalize(pointer));
        }
        return normalized.stream().sorted().toList();
    }

    public boolean managesPointer(String pointer) {
        return getNormalizedSensitivePointers().contains(JsonPointerUtils.canonicalize(pointer));
    }
}
