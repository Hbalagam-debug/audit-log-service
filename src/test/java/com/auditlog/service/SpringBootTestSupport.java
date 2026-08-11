package com.auditlog.service;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

public abstract class SpringBootTestSupport {
    protected static final String TEST_MASTER_KEY_BASE64 = "MDEyMzQ1Njc4OUFCQ0RFRjAxMjM0NTY3ODlBQkNERUY=";
    protected static final String TEST_EXPORT_SIGNING_PRIVATE_KEY_BASE64 = "MC4CAQAwBQYDK2VwBCIEICqzEHst90kO5+1+LQ8gnBQJT/bnN67SpvRMCMG93dlc";
    protected static final String TEST_EXPORT_SIGNING_PUBLIC_KEY_BASE64 = "MCowBQYDK2VwAyEAcXRkNXt9k6hrbp2g9Fr4MLccattwFD00s3s9TOs3l6w=";

    @DynamicPropertySource
    static void registerEncryptionProperties(DynamicPropertyRegistry registry) {
        registry.add("audit.encryption.master-key-base64", () -> TEST_MASTER_KEY_BASE64);
        registry.add("audit.export.signing.private-key-base64", () -> TEST_EXPORT_SIGNING_PRIVATE_KEY_BASE64);
        registry.add("audit.export.signing.public-key-base64", () -> TEST_EXPORT_SIGNING_PUBLIC_KEY_BASE64);
    }
}
