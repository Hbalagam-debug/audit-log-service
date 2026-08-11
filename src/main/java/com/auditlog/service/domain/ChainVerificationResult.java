package com.auditlog.service.domain;

public class ChainVerificationResult {
    private final boolean intact;
    private final int recordsChecked;
    private final String firstInconsistentRecordId;
    private final Long firstInconsistentPosition;
    private final ViolationType violationType;
    private final String message;

    public ChainVerificationResult(
        boolean intact,
        int recordsChecked,
        String firstInconsistentRecordId,
        Long firstInconsistentPosition,
        ViolationType violationType,
        String message
    ) {
        this.intact = intact;
        this.recordsChecked = recordsChecked;
        this.firstInconsistentRecordId = firstInconsistentRecordId;
        this.firstInconsistentPosition = firstInconsistentPosition;
        this.violationType = violationType;
        this.message = message;
    }

    public boolean isIntact() { return intact; }
    public int getRecordsChecked() { return recordsChecked; }
    public String getFirstInconsistentRecordId() { return firstInconsistentRecordId; }
    public Long getFirstInconsistentPosition() { return firstInconsistentPosition; }
    public ViolationType getViolationType() { return violationType; }
    public String getMessage() { return message; }
}
