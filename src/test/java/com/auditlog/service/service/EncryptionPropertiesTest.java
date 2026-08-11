package com.auditlog.service.service;

import com.auditlog.service.config.EncryptionProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("EncryptionProperties - Validation")
class EncryptionPropertiesTest {
    private static final String TEST_MASTER_KEY_BASE64 = "MDEyMzQ1Njc4OUFCQ0RFRjAxMjM0NTY3ODlBQkNERUY=";

    @Test
    @DisplayName("Rejects missing master key when encryption is enabled")
    void testRejectsMissingMasterKey() {
        EncryptionProperties properties = new EncryptionProperties();
        properties.setMasterKeyBase64("");

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    @DisplayName("Rejects overlapping parent and child pointers")
    void testRejectsOverlappingPointers() {
        EncryptionProperties properties = new EncryptionProperties();
        properties.setMasterKeyBase64(TEST_MASTER_KEY_BASE64);
        properties.setSensitivePointers(List.of("/account", "/account/id"));

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    @DisplayName("Accepts valid encryption configuration")
    void testAcceptsValidConfiguration() {
        EncryptionProperties properties = new EncryptionProperties();
        properties.setMasterKeyBase64(TEST_MASTER_KEY_BASE64);
        properties.setSensitivePointers(List.of("/accountNumber", "/personalIdentifier"));

        assertDoesNotThrow(properties::validate);
    }
}
