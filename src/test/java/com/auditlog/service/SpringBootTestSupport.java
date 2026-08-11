package com.auditlog.service;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

public abstract class SpringBootTestSupport {
    protected static final String TEST_MASTER_KEY_BASE64 = "MDEyMzQ1Njc4OUFCQ0RFRjAxMjM0NTY3ODlBQkNERUY=";
    protected static final String TEST_EXPORT_SIGNING_KEY_ID = "test-export-signing-key";
    protected static final KeyPair TEST_EXPORT_SIGNING_KEY_PAIR = generateSigningKeyPair();
    protected static final String TEST_EXPORT_SIGNING_PRIVATE_KEY_BASE64 = Base64.getEncoder()
        .encodeToString(TEST_EXPORT_SIGNING_KEY_PAIR.getPrivate().getEncoded());
    protected static final String TEST_EXPORT_SIGNING_PUBLIC_KEY_BASE64 = Base64.getEncoder()
        .encodeToString(TEST_EXPORT_SIGNING_KEY_PAIR.getPublic().getEncoded());

    @DynamicPropertySource
    static void registerEncryptionProperties(DynamicPropertyRegistry registry) {
        registry.add("audit.encryption.master-key-base64", () -> TEST_MASTER_KEY_BASE64);
        registry.add("audit.export.signing.enabled", () -> true);
        registry.add("audit.export.signing.key-id", () -> TEST_EXPORT_SIGNING_KEY_ID);
        registry.add("audit.export.signing.private-key-file", () -> "");
        registry.add("audit.export.signing.public-key-file", () -> "");
        registry.add("audit.export.signing.private-key-base64", () -> TEST_EXPORT_SIGNING_PRIVATE_KEY_BASE64);
        registry.add("audit.export.signing.public-key-base64", () -> TEST_EXPORT_SIGNING_PUBLIC_KEY_BASE64);
    }

    private static KeyPair generateSigningKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate test Ed25519 signing key pair", ex);
        }
    }
}
