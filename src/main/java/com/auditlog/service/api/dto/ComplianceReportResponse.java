package com.auditlog.service.api.dto;

import java.util.List;

public record ComplianceReportResponse(
    ComplianceReportSelection selection,
    long recordCount,
    List<AuditEventResponse> items,
    String nextCursor,
    boolean hasMore
) {
    public record ComplianceReportSelection(
        String selector,
        String selectorValue,
        String fromTimestamp,
        String toTimestamp,
        String actorIdFilter,
        String actionFilter,
        String outcomeFilter,
        boolean includeArchivedEvents
    ) {
    }
}
