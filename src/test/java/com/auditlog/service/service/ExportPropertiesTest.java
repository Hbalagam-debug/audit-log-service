package com.auditlog.service.service;

import com.auditlog.service.SpringBootTestSupport;
import com.auditlog.service.config.ExportProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("ExportProperties - Validation")
class ExportPropertiesTest extends SpringBootTestSupport {

    @Test
    @DisplayName("Rejects missing signing private key when signing is enabled")
    void testRejectsMissingSigningPrivateKey() {
        ExportProperties properties = new ExportProperties();
        properties.getSigning().setPrivateKeyBase64("");
        properties.getSigning().setPublicKeyBase64(TEST_EXPORT_SIGNING_PUBLIC_KEY_BASE64);

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    @DisplayName("Rejects invalid Base64 signing key configuration")
    void testRejectsInvalidBase64SigningKeyConfiguration() {
        ExportProperties properties = new ExportProperties();
        properties.getSigning().setPrivateKeyBase64("not-base64");
        properties.getSigning().setPublicKeyBase64(TEST_EXPORT_SIGNING_PUBLIC_KEY_BASE64);

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    @DisplayName("Rejects unsupported signing algorithm")
    void testRejectsUnsupportedSigningAlgorithm() {
        ExportProperties properties = new ExportProperties();
        properties.getSigning().setAlgorithm("RSA");
        properties.getSigning().setPrivateKeyBase64(TEST_EXPORT_SIGNING_PRIVATE_KEY_BASE64);
        properties.getSigning().setPublicKeyBase64(TEST_EXPORT_SIGNING_PUBLIC_KEY_BASE64);

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    @DisplayName("Accepts valid signing configuration")
    void testAcceptsValidSigningConfiguration() {
        ExportProperties properties = new ExportProperties();
        properties.getSigning().setPrivateKeyBase64(TEST_EXPORT_SIGNING_PRIVATE_KEY_BASE64);
        properties.getSigning().setPublicKeyBase64(TEST_EXPORT_SIGNING_PUBLIC_KEY_BASE64);

        assertDoesNotThrow(properties::validate);
    }
}
