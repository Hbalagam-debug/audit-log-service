package com.auditlog.service.service;

public class InvalidCursorException extends IllegalArgumentException {
    public InvalidCursorException(String message) {
        super(message);
    }
}
