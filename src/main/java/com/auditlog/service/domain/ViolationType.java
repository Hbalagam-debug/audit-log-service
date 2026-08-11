package com.auditlog.service.domain;

public enum ViolationType {
    CONTENT_HASH_MISMATCH,
    PREVIOUS_HASH_MISMATCH,
    CHAIN_HASH_MISMATCH,
    POSITION_GAP,
    INVALID_GENESIS_REFERENCE
}
