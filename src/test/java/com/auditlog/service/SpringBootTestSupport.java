package com.auditlog.service;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

public abstract class SpringBootTestSupport {
    private static final String TEST_MASTER_KEY_BASE64 = "MDEyMzQ1Njc4OUFCQ0RFRjAxMjM0NTY3ODlBQkNERUY=";

    @DynamicPropertySource
    static void registerEncryptionProperties(DynamicPropertyRegistry registry) {
        registry.add("audit.encryption.master-key-base64", () -> TEST_MASTER_KEY_BASE64);
    }
}
