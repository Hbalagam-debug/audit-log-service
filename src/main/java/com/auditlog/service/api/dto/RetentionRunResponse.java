package com.auditlog.service.api.dto;

public record RetentionRunResponse(
    String runId,
    String status,
    boolean dryRun,
    int retentionWindowDays,
    String cutoffTimestamp,
    int candidateCount,
    int archivedCount,
    String startedAt,
    String completedAt
) {
}
