package com.auditlog.service.domain;

public record RetentionRun(
    String id,
    String requestedBy,
    String approvedBy,
    String approvalRef,
    int windowDays,
    boolean dryRun,
    String cutoffTimestamp,
    String startedAt,
    String completedAt,
    int candidateCount,
    int archivedCount,
    String status,
    String reason
) {
}
