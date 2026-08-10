package com.auditlog.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration")
@ActiveProfiles("test")
class DatabaseInitializationTest {
    @Autowired
    ResourceLoader resourceLoader;

    @Test
    void schemaSqlContainsSchemaMetadata() throws Exception {
        Resource resource = resourceLoader.getResource("classpath:schema.sql");
        String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(content).contains("schema_metadata");
    }
}
