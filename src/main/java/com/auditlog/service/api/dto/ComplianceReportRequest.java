package com.auditlog.service.api.dto;

import jakarta.validation.constraints.NotNull;

public record ComplianceReportRequest(
    String accountId,
    String resourceId,
    @NotNull(message = "from is required")
    String from,
    @NotNull(message = "to is required")
    String to,
    String actorId,
    String action,
    String outcome,
    boolean includeArchived,
    String cursor,
    Integer limit
) {
}
