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
    @DisplayName("Rejects missing signing key id when signing is enabled")
    void testRejectsMissingSigningKeyId() {
        ExportProperties properties = new ExportProperties();
        properties.getSigning().setKeyId("");

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    @DisplayName("Rejects unsupported signing algorithm")
    void testRejectsUnsupportedSigningAlgorithm() {
        ExportProperties properties = new ExportProperties();
        properties.getSigning().setAlgorithm("RSA");
        properties.getSigning().setKeyId(TEST_EXPORT_SIGNING_KEY_ID);

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    @DisplayName("Allows disabled signing without key material")
    void testAllowsDisabledSigningWithoutKeyMaterial() {
        ExportProperties properties = new ExportProperties();
        properties.getSigning().setEnabled(false);
        properties.getSigning().setKeyId("");

        assertDoesNotThrow(properties::validate);
    }

    @Test
    @DisplayName("Accepts valid signing property configuration")
    void testAcceptsValidSigningConfiguration() {
        ExportProperties properties = new ExportProperties();
        properties.getSigning().setKeyId(TEST_EXPORT_SIGNING_KEY_ID);

        assertDoesNotThrow(properties::validate);
    }
}
