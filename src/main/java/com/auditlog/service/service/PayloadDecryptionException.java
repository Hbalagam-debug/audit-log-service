package com.auditlog.service.service;

public class PayloadDecryptionException extends RuntimeException {
    public PayloadDecryptionException() {
        super("Encrypted payload is unavailable for one or more events");
    }
}
