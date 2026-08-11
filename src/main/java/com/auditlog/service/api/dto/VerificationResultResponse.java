package com.auditlog.service.api.dto;

import com.auditlog.service.domain.ChainVerificationResult;
import com.auditlog.service.domain.ViolationType;

public record VerificationResultResponse(
    boolean intact,
    int recordsChecked,
    String firstInconsistentRecordId,
    Long firstInconsistentPosition,
    String violationType,
    String message
) {
    public static VerificationResultResponse fromDomain(ChainVerificationResult result) {
        return new VerificationResultResponse(
            result.isIntact(),
            result.getRecordsChecked(),
            result.getFirstInconsistentRecordId(),
            result.getFirstInconsistentPosition(),
            result.getViolationType() != null ? result.getViolationType().toString() : null,
            result.getMessage()
        );
    }
}
