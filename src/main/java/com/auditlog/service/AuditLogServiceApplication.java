package com.auditlog.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.File;

@SpringBootApplication
public class AuditLogServiceApplication {
    public static void main(String[] args) {
        // Ensure data directory exists before Spring Boot initializes the DataSource
        try {
            File dataDir = new File("./data");
            if (!dataDir.exists()) {
                dataDir.mkdirs();
            }
        } catch (Exception e) {
            System.err.println("Warning: could not create ./data directory: " + e.getMessage());
        }

        SpringApplication.run(AuditLogServiceApplication.class, args);
    }
}
