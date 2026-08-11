package com.auditlog.service.service;

public class RetentionRequestValidationException extends IllegalArgumentException {
    public RetentionRequestValidationException(String message) {
        super(message);
    }
}
