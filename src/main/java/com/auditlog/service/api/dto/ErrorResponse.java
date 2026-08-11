package com.auditlog.service.api.dto;

import java.time.Instant;
import java.util.List;

public record ErrorResponse(
    Instant timestamp,
    int status,
    String code,
    String message,
    String path,
    List<String> validationErrors
) {
    public ErrorResponse(
        int status,
        String code,
        String message,
        String path
    ) {
        this(Instant.now(), status, code, message, path, null);
    }

    public ErrorResponse(
        int status,
        String code,
        String message,
        String path,
        List<String> validationErrors
    ) {
        this(Instant.now(), status, code, message, path, validationErrors);
    }
}
