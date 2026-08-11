package com.auditlog.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

@SpringBootTest(properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration")
@EnableAutoConfiguration(excludeName = {"org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"})
class AuditLogServiceApplicationTests {
    @Test
    void contextLoads() {
    }
}
