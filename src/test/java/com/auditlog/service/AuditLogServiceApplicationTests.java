package com.auditlog.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootTest
@ActiveProfiles("test")
class AuditLogServiceApplicationTests {
    private static final Path tempDir = createTempDir();

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add(
            "spring.datasource.url",
            () -> "jdbc:sqlite:" + tempDir.resolve("context-loads.db").toAbsolutePath()
        );
    }

    @Test
    void contextLoads() {
    }

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("audit-log-context-");
        } catch (IOException ex) {
            throw new RuntimeException("Failed to create temporary database directory", ex);
        }
    }
}
