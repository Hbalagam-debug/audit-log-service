package com.auditlog.service.service;

import com.auditlog.service.config.RetentionProperties;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RetentionProperties - Validation")
class RetentionPropertiesTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("Rejects non-positive window days")
    void testRejectsNonPositiveWindowDays() {
        RetentionProperties properties = new RetentionProperties();
        properties.setWindowDays(0);

        Set<?> violations = validator.validate(properties);

        assertFalse(violations.isEmpty());
    }

    @Test
    @DisplayName("Accepts positive window days")
    void testAcceptsPositiveWindowDays() {
        RetentionProperties properties = new RetentionProperties();
        properties.setWindowDays(30);

        Set<?> violations = validator.validate(properties);

        assertTrue(violations.isEmpty());
    }
}
